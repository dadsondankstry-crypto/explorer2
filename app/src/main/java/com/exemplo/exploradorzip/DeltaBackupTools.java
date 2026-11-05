package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Backup incremental: escreve um ZIP NOVO com arquivos adicionados/alterados em relacao ao ZIP base.
 *  Nunca escreve na origem ou no ZIP base; nao realiza restauracao nem sincronizacao destrutiva. */
final class DeltaBackupTools {
  private static final int MAX_FILES=2500, MAX_DEPTH=24;
  private static final long MAX_TOTAL=512L*1024*1024, MAX_FILE=128L*1024*1024;
  private final MainActivity a;
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private volatile boolean running=false,cancel=false;
  private volatile int processed=0;
  private volatile String stage="Parado",report="Nenhuma analise.";
  private Uri source,baseline;
  private Plan plan;
  private boolean includeHidden=true;
  private static final class Item {
    final String path,id;final boolean dir;final long size;
    Item(String p,String i,boolean d,long s){path=p;id=i;dir=d;size=s;}
  }
  private static final class Plan {
    final Uri source,baseline;
    final ArrayList<Item> changed=new ArrayList<>();
    final ArrayList<String> removed=new ArrayList<>();
    int unchanged,added,modified;long knownBytes;
    Plan(Uri s,Uri b){source=s;baseline=b;}
  }
  DeltaBackupTools(MainActivity activity){a=activity;}
  void menu(){
    String[] options={"Escolher pasta ORIGEM", "Escolher ZIP de referencia (backup completo)",
      "Incluir arquivos ocultos: "+(includeHidden?"SIM":"NAO"),
      "1. Comparar e gerar PREVIA (SHA-256)","2. Salvar NOVO ZIP incremental", 
      "Progresso / solicitar cancelamento","Ver ultimo relatorio","Copiar relatorio","Limites e restauracao"};
    new AlertDialog.Builder(a).setTitle("Backup incremental 3.5").setItems(options,(d,k)->{
      switch(k){
        case 0: {Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
          i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);a.startActivityForResult(i,MainActivity.DELTA_SOURCE);break;}
        case 1: {Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
          i.setType("application/zip");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivityForResult(i,MainActivity.DELTA_BASELINE);break;}
        case 2:includeHidden=!includeHidden;plan=null;toast("Preferencia atualizada. Refaca a previa.");break;
        case 3:preview();break;
        case 4:confirmSave();break;
        case 5:show("Andamento",(running?"Em andamento":"Parado")+"\nEtapa: "+stage+"\nItens: "+processed);if(running)new AlertDialog.Builder(a).setMessage("Deseja solicitar cancelamento? (nao desfaz arquivos ja gravados)").setPositiveButton("Cancelar tarefa",(x,y)->cancel=true).setNegativeButton("Continuar",null).show();break;
        case 6:show("Relatorio",report);break;
        case 7:((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Backup incremental",report));toast("Relatorio copiado");break;
        case 8:show("Seguranca e limites","Ate 2500 itens, profundidade 24, 512 MiB de leitura total por etapa, 128 MiB por arquivo. O ZIP base deve representar a raiz da pasta selecionada; ZIPs com pasta principal produzem caminhos diferentes. Sao comparados hashes SHA-256 dos conteudos. O incremental contem apenas novos/alterados e o relatorio de removidos, mas NAO substitui o backup completo. Para restaurar, restaure o completo e depois aplique os incrementais na ordem, com uma ferramenta que suporte sobrescrita controlada. O Explorador nao restaura automaticamente a cadeia. Nenhum arquivo e excluido da origem.");break;
      }
    }).setNegativeButton("Fechar",null).show();
  }
  void selected(int request,Uri uri,Intent data){
    if(running){toast("Operacao em andamento");return;}
    if(request==MainActivity.DELTA_SOURCE){source=uri;persist(uri,data);plan=null;toast("Origem selecionada");}
    if(request==MainActivity.DELTA_BASELINE){baseline=uri;persist(uri,data);plan=null;toast("ZIP de referencia selecionado");}
    if(request==MainActivity.DELTA_OUTPUT)save(uri);
  }
  private void persist(Uri uri,Intent data){try{int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);a.getContentResolver().takePersistableUriPermission(uri,flags);}catch(Exception ignored){}}
  private void preview(){
    if(running)return;
    if(source==null||baseline==null){toast("Selecione a origem e o ZIP de referencia");return;}
    running=true;cancel=false;processed=0;stage="Lendo ZIP de referencia";plan=null;
    final Uri src=source,old=baseline;final boolean include=includeHidden;
    executor.execute(()->{
      try{
        Map<String,String> hashes=readBaselineHashes(old);
        stage="Analisando pasta de origem";
        LinkedHashMap<String,Item> found=scan(src,include);
        Plan p=new Plan(src,old);
        for(Item item:found.values()){
          check();if(item.dir)continue;
          String previous=hashes.remove(item.path);
          if(previous==null){p.changed.add(item);p.added++;}
          else {String now=hash(docUri(src,item.id));if(now.equals(previous))p.unchanged++;else{p.changed.add(item);p.modified++;}}
          if(item.size>0)p.knownBytes+=item.size;
          processed++;
        }
        p.removed.addAll(hashes.keySet());Collections.sort(p.removed);
        plan=p;StringBuilder b=new StringBuilder("PREVIA 3.5 — NENHUM ARQUIVO ALTERADO\n\n");
        b.append("Novos: ").append(p.added).append("\nModificados: ").append(p.modified)
          .append("\nIguais: ").append(p.unchanged).append("\nAusentes da origem: ").append(p.removed.size())
          .append("\nArquivos a incluir no ZIP incremental: ").append(p.changed.size())
          .append("\n\nNovos / alterados (ate 60):\n");
        for(int i=0;i<Math.min(60,p.changed.size());i++)b.append("+ ").append(p.changed.get(i).path).append('\n');
        b.append("\nAusentes (apenas relatorio, ate 60):\n");
        for(int i=0;i<Math.min(60,p.removed.size());i++)b.append("- ").append(p.removed.get(i)).append('\n');
        b.append("\nO backup completo original continua necessario para recuperar arquivos nao alterados.");
        finish(b.toString());
      }catch(Exception e){plan=null;finish("Falha na previa: "+e.getMessage());}
    });toast("Analise iniciada");
  }
  private void confirmSave(){
    if(running)return;
    if(plan==null){toast("Faca a previa primeiro");return;}
    if(!plan.source.equals(source)||!plan.baseline.equals(baseline)){toast("Refaca a previa");return;}
    new AlertDialog.Builder(a).setTitle("Salvar incremental?")
      .setMessage("Criar novo ZIP com "+plan.changed.size()+" arquivos novos/alterados e relatorio dos "+plan.removed.size()+" ausentes. Nenhum original sera removido ou sobrescrito. A origem pode ter mudado desde a previa; dados serao relidos ao salvar.")
      .setPositiveButton("Escolher destino",(d,n)->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"Incremental_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date())+".zip");
        a.startActivityForResult(i,MainActivity.DELTA_OUTPUT);}).setNegativeButton("Cancelar",null).show();
  }
  private void save(Uri output){
    if(running||plan==null){toast("Execute a previa primeiro");return;}
    if(output.equals(baseline)){toast("Escolha um novo destino, diferente da referencia");return;}
    final Plan p=plan;plan=null;running=true;cancel=false;processed=0;stage="Gravando ZIP incremental";
    executor.execute(()->{
      boolean success=false;String error="";int count=0;
      try(OutputStream raw=a.getContentResolver().openOutputStream(output,"w")){
        if(raw==null)throw new IOException("Nao foi possivel abrir destino");
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(raw))){
          zip.setLevel(6);byte[] buf=new byte[32768];
          for(Item item:p.changed){
            check();stage="Compactando "+item.path;
            ZipEntry ze=new ZipEntry(item.path);zip.putNextEntry(ze);
            try(InputStream in=a.getContentResolver().openInputStream(docUri(p.source,item.id))){
              if(in==null)throw new IOException("Arquivo indisponivel: "+item.path);
              int n;long fileBytes=0;
              while((n=in.read(buf))!=-1){check();fileBytes+=n;if(fileBytes>MAX_FILE)throw new IOException("Arquivo acima do limite: "+item.path);zip.write(buf,0,n);}
            }
            zip.closeEntry();count++;processed++;
          }
          StringBuilder manifest=new StringBuilder("# Backup incremental 3.5\n# Arquivos ausentes estao listados, NAO foram excluidos\n");
          for(String name:p.removed)manifest.append("AUSENTE: ").append(name).append('\n');
          ZipEntry info=new ZipEntry("INCREMENTAL_RELATORIO.txt");
          zip.putNextEntry(info);zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        success=true;
      }catch(Exception e){error=e.getMessage();}
      if(!success){try{DocumentsContract.deleteDocument(a.getContentResolver(),output);}catch(Exception ignored){}
        finish("Backup incremental falhou: "+error+"\nArquivo incompleto foi removido quando possivel. Originais intactos.");}
      else finish("Backup incremental criado.\nArquivos gravados: "+count+"\nAusentes apenas listados: "+p.removed.size()+"\nNao apague o backup completo de referencia.\nNenhum arquivo de origem foi modificado.");
    });toast("Backup incremental iniciado. Mantenha o app aberto.");
  }
  private Map<String,String> readBaselineHashes(Uri zipUri)throws Exception{
    HashMap<String,String> hashes=new HashMap<>();long total=0;int entries=0;
    try(InputStream raw=a.getContentResolver().openInputStream(zipUri)){
      if(raw==null)throw new IOException("ZIP indisponivel");
      try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(raw))){
        ZipEntry ze;byte[] buf=new byte[32768];
        while((ze=zip.getNextEntry())!=null){
          check();if(++entries>MAX_FILES)throw new IOException("ZIP com entradas demais");
          String name=ze.getName();if(!safePath(name))throw new IOException("Caminho inseguro no ZIP: "+name);
          if(ze.isDirectory())continue;
          // Manifestos gerados pelo backup anterior nao sao conteudo da pasta original.
          if(name.equals("BACKUP_SHA256.txt")||name.equals("INCREMENTAL_RELATORIO.txt"))continue;
          MessageDigest md=MessageDigest.getInstance("SHA-256");int n;long bytes=0;
          while((n=zip.read(buf))!=-1){check();bytes+=n;total+=n;
            if(bytes>MAX_FILE||total>MAX_TOTAL)throw new IOException("ZIP excedeu limites de leitura");md.update(buf,0,n);}
          if(hashes.put(name,hex(md.digest()))!=null)throw new IOException("Entrada duplicada no ZIP: "+name);
          processed++;
        }
      }
    }
    return hashes;
  }
  private LinkedHashMap<String,Item> scan(Uri tree,boolean include)throws Exception{
    LinkedHashMap<String,Item> result=new LinkedHashMap<>();
    ArrayDeque<Item> queue=new ArrayDeque<>();
    queue.add(new Item("",DocumentsContract.getTreeDocumentId(tree),true,0));
    while(!queue.isEmpty()){
      check();Item parent=queue.removeFirst();
      Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent.id);
      try(Cursor c=a.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
        if(c==null)throw new IOException("Nao foi possivel listar "+parent.path);
        while(c.moveToNext()){
          check();String name=c.getString(1);if(name==null||!safeSegment(name)||(!include&&name.startsWith(".")))continue;
          String path=parent.path.isEmpty()?name:parent.path+"/"+name;
          if(path.split("/",-1).length>MAX_DEPTH)throw new IOException("Profundidade excedida");
          boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
          Item it=new Item(path,c.getString(0),dir,c.isNull(3)?-1:c.getLong(3));
          if(result.put(path,it)!=null)throw new IOException("Caminho duplicado na origem: "+path);
          if(result.size()>MAX_FILES)throw new IOException("Limite de itens excedido");
          if(dir)queue.addLast(it);
        }
      }
    }
    return result;
  }
  private String hash(Uri uri)throws Exception{
    MessageDigest md=MessageDigest.getInstance("SHA-256");long bytes=0;
    try(InputStream in=a.getContentResolver().openInputStream(uri)){
      if(in==null)throw new IOException("Sem leitura: "+uri);
      byte[] buf=new byte[32768];int n;
      while((n=in.read(buf))!=-1){check();bytes+=n;if(bytes>MAX_FILE)throw new IOException("Arquivo maior que 128 MiB");md.update(buf,0,n);}
    }
    return hex(md.digest());
  }
  private Uri docUri(Uri tree,String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
  private static boolean safeSegment(String s){return !s.isEmpty()&&!s.equals(".")&&!s.equals("..")&&!s.contains("/")&&!s.contains("\\")&&!s.contains("\u0000");}
  private static boolean safePath(String s){
    if(s.startsWith("/")||s.startsWith("\\")||s.contains("\\")||s.contains(":"))return false;
    for(String segment:s.split("/"))if(!safeSegment(segment))return false;
    return true;
  }
  private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
  private void check()throws IOException{if(cancel)throw new IOException("Operacao cancelada");}
  private void finish(String message){a.runOnUiThread(()->{running=false;stage="Concluido";report=message;show("Backup 3.5",message);});}
  private void toast(String s){Toast.makeText(a,s,Toast.LENGTH_LONG).show();}
  private void show(String title,String message){new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Fechar",null).show();}
}
