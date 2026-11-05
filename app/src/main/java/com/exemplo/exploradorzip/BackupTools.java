package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;

/** Backup ZIP somente-leitura dos originais, sem exclusão e sem restauração automática. */
final class BackupTools {
  private final MainActivity a;
  private final ExecutorService pool=Executors.newSingleThreadExecutor();
  private volatile boolean running=false, cancel=false;
  private volatile int completed=0;
  private volatile String current="",report="Nenhum backup executado.";
  private volatile long bytes=0;
  private boolean selectedOnly=false, withContainer=false, checksum=true;
  private final ArrayList<String> entries=new ArrayList<>();
  private final int MAX_FILES=20000,MAX_DEPTH=32;
  private final long MAX_BYTES=1024L*1024*1024;

  BackupTools(MainActivity activity){a=activity;}

  void menu(){
    String[] actions={"Criar backup da pasta atual (incluindo subpastas)",
      "Criar backup somente da seleção", "Alternar pasta principal no ZIP: "+yes(withContainer),
      "Alternar manifesto SHA-256: "+yes(checksum), "Ver progresso / cancelar",
      "Ver relatório da última operação", "Copiar último relatório", "Verificar integridade de um ZIP",
      "Consultar configurações e limites", "Como restaurar um backup com segurança"};
    new AlertDialog.Builder(a).setTitle("Backups 3.0").setItems(actions,(d,which)->{
      switch(which){
        case 0: prepare(false);break;
        case 1: prepare(true);break;
        case 2: withContainer=!withContainer;a.fail("Pasta principal: "+yes(withContainer));break;
        case 3: checksum=!checksum;a.fail("Manifesto SHA-256: "+yes(checksum));break;
        case 4: state();break;
        case 5: message("Último relatório",report);break;
        case 6: copy();break;
        case 7: pickZip();break;
        case 8: message("Limites e segurança", "Máximo de 20 mil arquivos, profundidade de 32 pastas e 1 GiB de dados descompactados. O backup é criado em ZIP novo, sem alterar arquivos de origem. Para evitar incluir o backup dentro dele próprio, escolha uma pasta de destino fora da pasta de origem. O aplicativo não modifica as datas originais ao criar o ZIP, mas os metadados restaurados podem variar conforme o provedor SAF. SHA-256 verifica o conteúdo gravado; não é uma assinatura de autenticidade.");break;
        case 9: message("Recuperar arquivos", "Abra o ZIP pela opção 🗜 ZIP e use Extrair ZIP em nova pasta. Confira o conteúdo antes de substituir qualquer arquivo existente. Esta versão não sobrescreve automaticamente os originais e não promete desfazer operações antigas.");break;
      }
    }).setNegativeButton("Fechar",null).show();
  }
  private String yes(boolean v){return v?"SIM":"NÃO";}
  private void message(String title,String msg){new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
  private void prepare(boolean only){
    if(running){a.fail("Backup já está em andamento");return;}
    if(a.root==null||a.docId==null||a.inZip){a.fail("Abra uma pasta normal primeiro");return;}
    if(only&&a.selectedFiles().isEmpty()){a.fail("Selecione arquivos com toque prolongado");return;}
    selectedOnly=only;
    String msg=(only?"Itens selecionados: "+a.selectedFiles().size():"Todo o conteúdo da pasta atual")+"\nInclui subpastas e nomes ocultos acessíveis.\nPasta principal: "+yes(withContainer)+"\nManifesto SHA-256: "+yes(checksum)+"\nOriginais nunca serão excluídos.\nEscolha DESTINO FORA da origem para evitar incluir o backup nele mesmo.";
    new AlertDialog.Builder(a).setTitle("Confirmar backup").setMessage(msg)
      .setNegativeButton("Cancelar",null).setPositiveButton("Escolher destino",(d,w)->a.chooseFolder(MainActivity.BACKUP_DESTINATION)).show();
  }
  void destinationSelected(Uri destination,Intent data){
    if(running)return;
    if(a.root==null||a.docId==null||a.inZip){a.fail("Pasta de origem indisponível");return;}
    try{int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      if(flags!=0)a.getContentResolver().takePersistableUriPermission(destination,flags);
    }catch(Exception ignored){}
    final Uri sourceTree=a.root;final String sourceId=a.docId;
    // Em provedores com IDs hierárquicos, rejeitar destino dentro da origem.
    try {
      String destId=DocumentsContract.getTreeDocumentId(destination);
      if(sourceTree.getAuthority().equals(destination.getAuthority()) &&
        (destId.equals(sourceId) || destId.startsWith(sourceId+"/") || destId.startsWith(sourceId+"%2F"))){
        a.fail("Escolha um destino fora da pasta de origem para evitar backup recursivo");return;
      }
    }catch(Exception ignored){}
    final boolean justSelection=selectedOnly,container=withContainer,hashes=checksum;
    final ArrayList<MainActivity.Row> chosen=new ArrayList<>(a.selectedFiles());
    if(justSelection&&chosen.isEmpty()){a.fail("Seleção vazia");return;}
    String backupName="Backup_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date())+".zip";
    new AlertDialog.Builder(a).setTitle("Criar "+backupName).setMessage("Confirme que a pasta de destino não está dentro da pasta de origem. O backup não altera os originais. Não há substituição automática de arquivos existentes.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Criar",(d,w)->runBackup(sourceTree,sourceId,destination,chosen,justSelection,container,hashes,backupName)).show();
  }
  private void runBackup(Uri tree,String srcId,Uri destTree,ArrayList<MainActivity.Row> chosen,boolean only,boolean container,boolean hashes,String filename){
    running=true;cancel=false;completed=0;bytes=0;current="Preparando";entries.clear();
    pool.execute(()->{
      Uri created=null;String error=null;
      try{
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(destTree,DocumentsContract.getTreeDocumentId(destTree));
        created=DocumentsContract.createDocument(a.getContentResolver(),parent,"application/zip",filename);
        if(created==null)throw new IOException("Não foi possível criar o ZIP no destino");
        Uri source=DocumentsContract.buildDocumentUriUsingTree(tree,srcId);
        String prefix=container?"Backup/":"";
        try(OutputStream raw=a.getContentResolver().openOutputStream(created,"w")){
          if(raw==null)throw new IOException("Destino sem permissão de escrita");
          try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(raw))){
            zip.setLevel(6);
            HashSet<String> names=new HashSet<>();
            if(only){for(MainActivity.Row r:chosen){checkCancel();walk(tree,DocumentsContract.buildDocumentUriUsingTree(tree,r.id),prefix+r.name,0,zip,names,hashes);}}
            else {walkChildren(tree,source,prefix,0,zip,names,hashes);}
            if(hashes){StringBuilder manifest=new StringBuilder("# SHA-256 | bytes | entrada\n");for(String entry:entries)manifest.append(entry).append('\n');
              String manifestName=prefix+"BACKUP_SHA256.txt";
              if(names.add(manifestName)){zip.putNextEntry(new ZipEntry(manifestName));zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
            }
          }
        }
        // Reabrir o backup e ler todos os bytes: verifica integridade básica das entradas ZIP.
        verifyZip(created);
      }catch(Exception e){error=e.getMessage();if(created!=null)try{DocumentsContract.deleteDocument(a.getContentResolver(),created);}catch(Exception ignored){}}
      final String err=error;final int count=completed;final long size=bytes;
      report=(err==null?"BACKUP CONCLUÍDO\n":"BACKUP NÃO CONCLUÍDO\n")+"Arquivo: "+filename+"\nArquivos: "+count+"\nBytes originais: "+size+"\nManifesto: "+yes(hashes)+"\n"+(err==null?"ZIP relido sem erro de CRC detectável.":"Motivo: "+err+"\nArquivo parcial removido quando possível.");
      a.runOnUiThread(()->{running=false;a.addLog((err==null?"Backup criado: ":"Falha de backup: ")+filename);message("Backups 3.0",report);});
    });
  }
  private void checkCancel()throws IOException{if(cancel)throw new IOException("Cancelado pelo usuário");if(completed>MAX_FILES||bytes>MAX_BYTES)throw new IOException("Limite de backup excedido");}
  private void walkChildren(Uri tree,Uri folder,String prefix,int depth,ZipOutputStream zip,Set<String> names,boolean hashes)throws Exception{
    if(depth>MAX_DEPTH)throw new IOException("Profundidade excedida");
    String id=DocumentsContract.getDocumentId(folder);
    Uri childUri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
    ArrayList<String[]> children=new ArrayList<>();
    try(Cursor c=a.getContentResolver().query(childUri,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c==null)throw new IOException("Não foi possível ler subpasta");
      while(c.moveToNext()){String n=c.getString(1);if(n!=null)children.add(new String[]{c.getString(0),n});}
    }
    for(String[] child:children){checkCancel();if(!valid(child[1]))throw new IOException("Nome inválido: "+child[1]);walk(tree,DocumentsContract.buildDocumentUriUsingTree(tree,child[0]),prefix+child[1],depth+1,zip,names,hashes);}
  }
  private boolean valid(String n){return n!=null&&!n.isEmpty()&&!n.equals(".")&&!n.equals("..")&&!n.contains("/")&&!n.contains("\\")&&!n.contains("\n")&&!n.contains("\r");}
  private void walk(Uri tree,Uri doc,String name,int depth,ZipOutputStream zip,Set<String> names,boolean hashes)throws Exception{
    checkCancel();if(depth>MAX_DEPTH)throw new IOException("Profundidade excedida");
    String mime=a.getMime(doc);boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    current=name;
    if(folder){String n=name.endsWith("/")?name:name+"/";if(names.add(n)){zip.putNextEntry(new ZipEntry(n));zip.closeEntry();}walkChildren(tree,doc,n,depth,zip,names,hashes);return;}
    if(!names.add(name))throw new IOException("Nome duplicado: "+name);
    MessageDigest md=hashes?MessageDigest.getInstance("SHA-256"):null;
    long itemSize=0;
    try(InputStream input=a.getContentResolver().openInputStream(doc)){
      if(input==null)throw new IOException("Sem leitura para "+name);
      ZipEntry entry=new ZipEntry(name);zip.putNextEntry(entry);
      byte[] buf=new byte[32768];int n;
      while((n=input.read(buf))!=-1){checkCancel();bytes+=n;itemSize+=n;if(bytes>MAX_BYTES)throw new IOException("Limite 1 GiB excedido");zip.write(buf,0,n);if(md!=null)md.update(buf,0,n);}
      zip.closeEntry();
    }
    completed++;
    if(hashes){StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));entries.add(h+" | "+itemSize+" | "+name);}
  }
  private void verifyZip(Uri uri)throws Exception{
    try(InputStream raw=a.getContentResolver().openInputStream(uri)){
      if(raw==null)throw new IOException("Falha ao reabrir ZIP");
      try(ZipInputStream zin=new ZipInputStream(new BufferedInputStream(raw))){ZipEntry entry;byte[] buffer=new byte[32768];int files=0;long size=0;
        while((entry=zin.getNextEntry())!=null){checkCancel();int n;while((n=zin.read(buffer))!=-1){size+=n;if(size>MAX_BYTES+16*1024*1024)throw new IOException("Limite de verificação excedido");}zin.closeEntry();files++;if(files>MAX_FILES+100)throw new IOException("Muitas entradas");}
      }
    }
  }
  private void state(){
    String txt="Em andamento: "+yes(running)+"\nArquivos concluídos: "+completed+"\nBytes lidos: "+bytes+"\nArquivo atual: "+current+"\nCancelamento solicitado: "+yes(cancel);
    new AlertDialog.Builder(a).setTitle("Backup · progresso").setMessage(txt).setPositiveButton("OK",null)
      .setNegativeButton(running?"Cancelar backup":"Fechar",(d,w)->{if(running){cancel=true;a.fail("Cancelamento solicitado");}}).show();
  }
  private void copy(){ClipboardManager c=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText("backup",report));a.fail("Relatório copiado");}
  private void pickZip(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");a.startActivityForResult(i,MainActivity.BACKUP_VALIDATE);}
  void validateSelected(Uri file){if(running){a.fail("Termine o backup antes de verificar outro ZIP");return;}running=true;cancel=false;completed=0;bytes=0;current="Verificando ZIP";
    pool.execute(()->{String error=null;try{verifyZip(file);}catch(Exception e){error=e.getMessage();}final String err=error;report=err==null?"Leitura das entradas ZIP concluída sem erros detectados. Isto não garante origem confiável nem autenticação do conteúdo.":"Erro na leitura do ZIP: "+err;
      a.runOnUiThread(()->{running=false;message("Verificar ZIP",report);});});
  }
}
