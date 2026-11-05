package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Restauro de ZIP por Storage Access Framework: nunca remove originais e nunca sobrescreve. */
final class RestoreTools {
  private static final int MAX_ENTRIES=5000;
  private static final long MAX_BYTES=1024L*1024*1024; // 1 GiB descompactado
  private static final long MAX_FILE=256L*1024*1024;
  private final MainActivity a;
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private volatile boolean busy=false,cancel=false;
  private volatile String active="",report="Ainda não há restauração.",progress="";
  private Uri zipUri,folderUri;
  private String zipName="";
  private final ArrayList<String> entries=new ArrayList<>();
  private final HashSet<String> chosen=new HashSet<>();
  private boolean restoreAll=true;
  private volatile int completed=0,total=0;
  RestoreTools(MainActivity a){this.a=a;}

  void menu(){
    String[] options={"Escolher ZIP para restaurar", "Escolher pasta de destino", "Listar arquivos do ZIP",
      "Selecionar itens (até 150 por tela)", "Modo: "+(restoreAll?"restaurar tudo":"restaurar selecionados"),
      "Prévia / verificar conflitos", "INICIAR restauração (não sobrescreve)",
      "Progresso / cancelar", "Ver relatório", "Copiar relatório", "Limites e segurança"};
    new AlertDialog.Builder(a).setTitle("Restauração 3.2").setItems(options,(d,i)->{
      switch(i){
        case 0:pickZip();break;case 1:pickFolder();break;
        case 2:loadEntries(false);break;case 3:loadEntries(true);break;
        case 4:restoreAll=!restoreAll;toast(restoreAll?"Todos os arquivos":"Somente selecionados");break;
        case 5:preflight(false);break;case 6:preflight(true);break;
        case 7:state();break;case 8:popup("Relatório",report);break;
        case 9:copyReport();break;
        case 10:popup("Segurança","Somente ZIP, não sobrescreve arquivos existentes. Arquivos restaurados são NOVAS cópias: datas de criação podem mudar. Não exclui arquivos originais. Até 5000 entradas, 1 GiB de conteúdo e 256 MiB por arquivo. Cancelamento entre leituras; operação parcialmente concluída permanece. Pastas protegidas do Android continuam inacessíveis. ZIPs com senhas não são suportados.");break;
      }
    }).setNegativeButton("Fechar",null).show();
  }
  private void pickZip(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivityForResult(i,MainActivity.RESTORE_ZIP);}
  private void pickFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);a.startActivityForResult(i,MainActivity.RESTORE_FOLDER);}
  void selected(int req,Uri uri,Intent intent){
    if(busy){toast("Há uma operação em andamento");return;}
    if(req==MainActivity.RESTORE_ZIP){zipUri=uri;zipName=a.queryName(uri);entries.clear();chosen.clear();toast("ZIP selecionado: "+zipName);}
    else {
      int flags=intent.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      try {a.getContentResolver().takePersistableUriPermission(uri,flags);}catch(Exception e){toast("Permissão não permanente: "+e.getMessage());}
      folderUri=uri;toast("Destino selecionado");
    }
  }
  private String checkedPath(String path)throws IOException{
    if(path==null||path.isEmpty()||path.startsWith("/")||path.startsWith("\\")||path.contains("\\")||path.indexOf('\0')>=0)throw new IOException("Nome inseguro");
    String[] parts=path.split("/",-1);
    for(int i=0;i<parts.length;i++){
      String p=parts[i];
      if(p.equals("..")||p.equals(".")||p.isEmpty()&&(i!=parts.length-1))throw new IOException("Caminho inseguro: "+path);
      if(p.indexOf(':')>=0||p.length()>200)throw new IOException("Componente de nome inválido: "+path);
    }
    return path;
  }
  private void loadEntries(boolean choose){
    if(zipUri==null){toast("Selecione um arquivo ZIP primeiro");return;}
    if(busy){toast("Operação em andamento");return;}
    busy=true;cancel=false;active="Lendo ZIP";
    executor.execute(()->{
      ArrayList<String> found=new ArrayList<>();String error=null;
      try(InputStream in=a.getContentResolver().openInputStream(zipUri);ZipInputStream zip=new ZipInputStream(new BufferedInputStream(in))){
        ZipEntry e;while((e=zip.getNextEntry())!=null){
          if(found.size()>=MAX_ENTRIES)throw new IOException("Mais de 5000 entradas");
          String name=checkedPath(e.getName());
          if(!e.isDirectory()&&!name.endsWith("/"))found.add(name);
          zip.closeEntry();
        }
      }catch(Exception ex){error=ex.getMessage();}
      final String problem=error;
      a.runOnUiThread(()->{
        busy=false;
        if(problem!=null){popup("Leitura não concluída",problem);return;}
        entries.clear();entries.addAll(found);
        if(!choose){StringBuilder b=new StringBuilder("Arquivos: "+entries.size()+"\n\n");for(int j=0;j<Math.min(120,entries.size());j++)b.append(entries.get(j)).append('\n');if(entries.size()>120)b.append("... lista reduzida na tela");popup("Conteúdo do ZIP",b.toString());}
        else selectPage(0);
      });
    });
  }
  private void selectPage(int offset){
    int end=Math.min(entries.size(),offset+150);
    if(end<=offset){toast("Não há itens para selecionar");return;}
    String[] labels=new String[end-offset];boolean[] checked=new boolean[labels.length];
    for(int j=offset;j<end;j++){labels[j-offset]=entries.get(j);checked[j-offset]=chosen.contains(labels[j-offset]);}
    new AlertDialog.Builder(a).setTitle("Escolher arquivos "+(offset+1)+"–"+end+" de "+entries.size())
      .setMultiChoiceItems(labels,checked,(dialog,index,on)->{if(on)chosen.add(labels[index]);else chosen.remove(labels[index]);})
      .setPositiveButton(end<entries.size()?"Próxima página":"Concluir",(d,w)->{restoreAll=false;if(end<entries.size())selectPage(end);else toast(chosen.size()+" selecionados");})
      .setNeutralButton("Concluir seleção",(d,w)->{restoreAll=false;toast(chosen.size()+" selecionados");})
      .setNegativeButton("Cancelar",null).show();
  }
  private boolean wanted(String name){return restoreAll||chosen.contains(name);}
  private void preflight(boolean willRestore){
    if(zipUri==null||folderUri==null){toast("Escolha o ZIP e a pasta de destino");return;}
    if(!restoreAll&&chosen.isEmpty()){toast("Selecione arquivos do ZIP ou use restaurar tudo");return;}
    if(busy){toast("Operação em andamento");return;}
    busy=true;cancel=false;completed=0;progress="Pré-verificação";
    final Uri input=zipUri,folder=folderUri;final boolean all=restoreAll;
    final HashSet<String> selected=new HashSet<>(chosen);
    executor.execute(()->{
      int selectedCount=0, conflicts=0;long bytes=0;
      String error=null;ArrayList<String> conflictNames=new ArrayList<>();HashSet<String> seen=new HashSet<>();
      try(InputStream raw=a.getContentResolver().openInputStream(input); ZipInputStream zin=new ZipInputStream(new BufferedInputStream(raw))){
        if(raw==null)throw new IOException("ZIP inacessível");
        ZipEntry e;int count=0;byte[] buf=new byte[32768];
        Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
        while((e=zin.getNextEntry())!=null){
          if(cancel)throw new IOException("Cancelado");
          if(++count>MAX_ENTRIES)throw new IOException("Mais de 5000 entradas");
          String name=checkedPath(e.getName());
          if(!seen.add(name))throw new IOException("Entrada duplicada: "+name);
          if(e.isDirectory()||name.endsWith("/")){zin.closeEntry();continue;}
          if(!all&&!selected.contains(name)){zin.closeEntry();continue;}
          selectedCount++;active=name;
          String[] parts=name.split("/");
          Uri parent=rootDoc; boolean unknownParent=false;
          for(int k=0;k<parts.length-1;k++){Uri existing=child(parent,parts[k]);if(existing==null){unknownParent=true;break;}parent=existing;}
          if(!unknownParent&&child(parent,parts[parts.length-1])!=null){conflicts++;if(conflictNames.size()<30)conflictNames.add(name);}
          long fileBytes=0;int n;
          while((n=zin.read(buf))!=-1){fileBytes+=n;bytes+=n;if(fileBytes>MAX_FILE||bytes>MAX_BYTES)throw new IOException("Limite de tamanho atingido");if(cancel)throw new IOException("Cancelado");}
          zin.closeEntry();
        }
      }catch(Exception ex){error=ex.getMessage();}
      final int n=selectedCount,c=conflicts;final long size=bytes;final String fail=error;
      final String msg="Arquivos candidatos: "+n+"\nConflitos detectados: "+c+"\nBytes lidos: "+size+"\n"+(c>0?"Serão ignorados arquivos existentes; nenhum será sobrescrito.\n"+String.join("\n",conflictNames):"Nenhum conflito detectado nas pastas já existentes.")+"\n\nA restauração criará novos arquivos. Pode alterar datas. Cancelamento não desfaz arquivos anteriores.";
      a.runOnUiThread(()->{busy=false;if(fail!=null){popup("Pré-verificação falhou",fail);return;}total=n;if(!willRestore)popup("Prévia da restauração",msg);
        else new AlertDialog.Builder(a).setTitle("Confirmar restauração").setMessage(msg).setPositiveButton("RESTAURAR",(d,w)->restore(input,folder,all,selected)).setNegativeButton("Cancelar",null).show();
      });
    });
  }
  private Uri child(Uri parent,String name)throws Exception{
    String id=DocumentsContract.getDocumentId(parent);
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folderUri,id);
    try(Cursor c=a.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(folderUri,c.getString(0));
    }
    return null;
  }
  private Uri ensureFolder(Uri parent,String name)throws Exception{
    Uri existing=child(parent,name);
    if(existing!=null){String mime="";try(Cursor c=a.getContentResolver().query(existing,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){if(c!=null&&c.moveToFirst())mime=c.getString(0);}if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))throw new IOException("Arquivo impede criar pasta: "+name);return existing;}
    Uri u=DocumentsContract.createDocument(a.getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,name);
    if(u==null)throw new IOException("Não foi possível criar pasta "+name);
    return u;
  }
  private void restore(Uri input,Uri folder,boolean all,Set<String> selected){
    busy=true;cancel=false;completed=0;progress="Iniciando";
    executor.execute(()->{
      int restored=0,skipped=0,failed=0;long bytes=0;String error=null;StringBuilder log=new StringBuilder("RESTAURAÇÃO 3.2\nZIP: "+zipName+"\n\n");
      try(InputStream raw=a.getContentResolver().openInputStream(input);ZipInputStream zin=new ZipInputStream(new BufferedInputStream(raw))){
        if(raw==null)throw new IOException("ZIP inacessível");
        ZipEntry e;byte[] buf=new byte[32768];int count=0;
        Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
        while((e=zin.getNextEntry())!=null){
          if(cancel)throw new IOException("Interrompido pelo usuário");
          if(++count>MAX_ENTRIES)throw new IOException("Limite de entradas excedido");
          String name=checkedPath(e.getName());active=name;progress="Processando "+name;
          if(e.isDirectory()||name.endsWith("/")){zin.closeEntry();continue;}
          if(!all&&!selected.contains(name)){zin.closeEntry();continue;}
          String[] parts=name.split("/");Uri parent=rootDoc;
          for(int j=0;j<parts.length-1;j++)parent=ensureFolder(parent,parts[j]);
          String basename=parts[parts.length-1];
          if(child(parent,basename)!=null){skipped++;log.append("IGNORADO (existente): ").append(name).append('\n');zin.closeEntry();completed++;continue;}
          Uri created=DocumentsContract.createDocument(a.getContentResolver(),parent,"application/octet-stream",basename);
          if(created==null){failed++;log.append("FALHOU: ").append(name).append('\n');zin.closeEntry();completed++;continue;}
          boolean good=false;long fileBytes=0;
          try(OutputStream out=a.getContentResolver().openOutputStream(created,"w")){
            if(out==null)throw new IOException("Destino sem acesso para escrita");
            int n;while((n=zin.read(buf))!=-1){
              if(cancel)throw new IOException("Cancelado");
              fileBytes+=n;bytes+=n;
              if(fileBytes>MAX_FILE||bytes>MAX_BYTES)throw new IOException("Limite de tamanho atingido");
              out.write(buf,0,n);
            }
            out.flush();good=true;restored++;
          }catch(Exception ex){failed++;log.append("ERRO ").append(name).append(": ").append(ex.getMessage()).append('\n');
            try{DocumentsContract.deleteDocument(a.getContentResolver(),created);}catch(Exception ignored){}
            if(cancel)throw ex;
          }
          zin.closeEntry();completed++;
          if(good)log.append("OK: ").append(name).append('\n');
        }
      }catch(Exception ex){error=ex.getMessage();}
      log.insert(0,"Restaurados: "+restored+"\nIgnorados (já existentes): "+skipped+"\nFalhas: "+failed+"\nBytes: "+bytes+"\n"+(error==null?"Concluído":"PARCIAL / INTERROMPIDO: "+error)+"\n\n");
      final String text=log.toString();report=text;
      a.runOnUiThread(()->{busy=false;popup("Resultado da restauração",text.length()>15000?text.substring(0,15000)+"\n... relatório abreviado":text);});
    });
  }
  private void state(){new AlertDialog.Builder(a).setTitle("Progresso 3.2").setMessage("Executando: "+busy+"\nConcluídos: "+completed+" / "+total+"\nAtual: "+active+"\n"+progress).setPositiveButton("Fechar",null).setNegativeButton(busy?"Solicitar cancelamento":"OK",(d,w)->{if(busy){cancel=true;toast("Cancelamento solicitado");}}).show();}
  private void copyReport(){ClipboardManager cb=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("relatorio_restore",report));toast("Relatório copiado");}
  private void popup(String title,String body){new AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
  private void toast(String msg){Toast.makeText(a,msg,Toast.LENGTH_LONG).show();}
}
