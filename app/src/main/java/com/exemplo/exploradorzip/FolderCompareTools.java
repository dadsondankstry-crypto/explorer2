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
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compara duas arvores concedidas pelo usuario, sem gravar nem apagar arquivos. */
final class FolderCompareTools {
  private static final int MAX_ITEMS=3500,MAX_DEPTH=32;
  private static final long MAX_HASH_BYTES=16L*1024*1024;
  private final MainActivity activity;
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private Uri left,right;
  private String leftLabel="A",rightLabel="B", lastReport="Nenhum relatorio ainda.";
  private volatile boolean running=false,cancel=false;
  private volatile int scanned=0;
  private volatile String stage="Parado";
  private boolean contentCompare=false, includeHidden=true;
  private static class Item {
    String relative,id;boolean directory;long size=-1,modified=-1;
    Item(String p,String id,boolean dir,long sz,long tm){relative=p;this.id=id;directory=dir;size=sz;modified=tm;}
  }
  FolderCompareTools(MainActivity a){activity=a;}
  void menu(){
    String[] options={"Escolher pasta A (origem da comparacao)","Escolher pasta B (referencia)",
      "Comparar pastas - PREVIA (somente leitura)",
      "Comparar conteudo por SHA-256: "+(contentCompare?"ATIVADO":"DESATIVADO"),
      "Incluir arquivos ocultos: "+(includeHidden?"SIM":"NAO"),
      "Progresso / cancelar", "Mostrar ultimo relatorio", "Copiar relatorio", "Limpar selecao", "Seguranca e limites"};
    new AlertDialog.Builder(activity).setTitle("Comparar pastas 3.3")
      .setItems(options,(d,pos)->{
        switch(pos){
          case 0:pick(MainActivity.COMPARE_A);break;
          case 1:pick(MainActivity.COMPARE_B);break;
          case 2:compare();break;
          case 3:contentCompare=!contentCompare;toast("SHA-256 "+(contentCompare?"ativado":"desativado"));break;
          case 4:includeHidden=!includeHidden;toast("Ocultos "+(includeHidden?"incluidos":"ignorados"));break;
          case 5:progress();break;
          case 6:show("Ultimo relatorio",lastReport);break;
          case 7:copy();break;
          case 8:if(!running){left=null;right=null;lastReport="Nenhum relatorio ainda.";toast("Selecoes limpas");}break;
          case 9:show("Seguranca", "Comparacao APENAS de leitura: nao sincroniza, nao copia, nao move e nao remove documentos. Ate 3500 itens por pasta, profundidade ate 32. Hash somente para arquivos de ate 16 MiB cada. Nem todo provedor fornece data ou tamanho. Arquivos invisiveis ou proibidos pelo Android nao serao examinados. Nao confunda relatorio com backup nem com verificacao integral de integridade.");break;
        }
      }).setNegativeButton("Fechar",null).show();
  }
  private void pick(int req){
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    activity.startActivityForResult(i,req);
  }
  void selected(int request,Uri uri,Intent data){
    if(running){toast("Comparacao em andamento");return;}
    try {activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception e){toast("Permissao temporaria: "+e.getMessage());}
    String label=uri.getLastPathSegment();
    if(request==MainActivity.COMPARE_A){left=uri;leftLabel=label;}else{right=uri;rightLabel=label;}
    toast("Pasta "+(request==MainActivity.COMPARE_A?"A":"B")+" selecionada");
  }
  private void compare(){
    if(left==null||right==null){toast("Selecione as duas pastas");return;}
    if(left.equals(right)){toast("Escolha duas pastas diferentes");return;}
    if(running){toast("Comparacao em andamento");return;}
    final Uri a=left,b=right;
    final boolean hash=contentCompare,hidden=includeHidden;
    running=true;cancel=false;scanned=0;stage="Lendo pasta A";
    executor.execute(()->{
      String result;
      try{
        TreeMap<String,Item> oldMap=scan(a,hidden);
        stage="Lendo pasta B";
        TreeMap<String,Item> newMap=scan(b,hidden);
        stage="Comparando";
        int same=0,changed=0,added=0,removed=0,unknown=0,folders=0,hashCount=0;
        ArrayList<String> lines=new ArrayList<>();
        for(String name:oldMap.keySet()){
          checkCancel();Item original=oldMap.get(name),fresh=newMap.get(name);
          if(fresh==null){removed++;lines.add("SOMENTE A  "+name);continue;}
          if(original.directory!=fresh.directory){changed++;lines.add("TIPO DIFERENTE  "+name);continue;}
          if(original.directory){folders++;continue;}
          boolean definite=false, unclear=false;
          if(original.size>=0&&fresh.size>=0&&original.size!=fresh.size)definite=true;
          else if(hash && original.size>=0 && fresh.size>=0 && original.size<=MAX_HASH_BYTES && fresh.size<=MAX_HASH_BYTES){
            stage="Comparando SHA-256: "+name;
            byte[] h1=digest(a,original.id),h2=digest(b,fresh.id);
            hashCount+=2;
            definite=!Arrays.equals(h1,h2);
          } else if(original.modified>0&&fresh.modified>0&&original.modified!=fresh.modified){
            unclear=true; // horarios diferentes nao garantem mudanca de conteudo
          } else if(original.size<0||fresh.size<0){unclear=true;}
          if(definite){changed++;lines.add("CONTEUDO/TAMANHO DIFERENTE  "+name);}
          else if(unclear){unknown++;lines.add("POSSIVEL DIFERENCA / METADADOS INCOMPLETOS  "+name);}
          else same++;
        }
        for(String name:newMap.keySet())if(!oldMap.containsKey(name)){added++;lines.add("SOMENTE B  "+name);}
        Collections.sort(lines);
        StringBuilder out=new StringBuilder();
        out.append("COMPARACAO DE PASTAS 3.3 — SOMENTE LEITURA\n\n");
        out.append("Pasta A: ").append(leftLabel).append("\nPasta B: ").append(rightLabel).append("\n");
        out.append("Arquivos ou diretorios A: ").append(oldMap.size()).append("\nB: ").append(newMap.size()).append("\n\n");
        out.append("Somente A: ").append(removed).append("\nSomente B: ").append(added)
          .append("\nDiferentes: ").append(changed).append("\nPossivelmente diferentes: ").append(unknown)
          .append("\nSem diferenca detectada: ").append(same).append("\nPastas coincidentes: ").append(folders)
          .append("\nArquivos verificados via SHA-256: ").append(hashCount).append("\n\n");
        out.append("IMPORTANTE: sem diferenca detectada NAO significa bytes iguais se o modo SHA-256 estava desligado.\n");
        out.append("Nao houve movimentacao nem exclusao.\n\nDETALHES:\n");
        int count=0;for(String line:lines){if(count++>=800){out.append("... detalhes limitados a 800 linhas\n");break;}out.append(line).append('\n');}
        result=out.toString();
      }catch(Exception ex){result="Comparacao interrompida ou incompleta: "+ex.getMessage()+"\nNenhum arquivo foi alterado.";}
      final String report=result;
      activity.runOnUiThread(()->{running=false;stage="Concluido";lastReport=report;show("Resultado da comparacao",report);});
    });
    toast("Comparacao iniciada. Acompanhe em Progresso.");
  }
  private TreeMap<String,Item> scan(Uri tree,boolean hidden)throws IOException{
    TreeMap<String,Item> result=new TreeMap<>();
    ArrayDeque<Item> pending=new ArrayDeque<>();
    pending.add(new Item("",DocumentsContract.getTreeDocumentId(tree),true,0,0));
    while(!pending.isEmpty()){
      checkCancel();Item parent=pending.removeFirst();
      if(parent.relative.split("/",-1).length>MAX_DEPTH)throw new IOException("Limite de profundidade");
      Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent.id);
      try(Cursor c=activity.getContentResolver().query(children,new String[]{
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,
          DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
        if(c==null)throw new IOException("Pasta inacessivel: "+parent.relative);
        while(c.moveToNext()){
          checkCancel();if(++scanned>MAX_ITEMS*2)throw new IOException("Limite de 3500 itens por pasta");
          String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
          if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/"))continue;
          if(!hidden&&name.startsWith("."))continue;
          String path=parent.relative.isEmpty()?name:parent.relative+"/"+name;
          boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
          long size=c.isNull(3)?-1:c.getLong(3),date=c.isNull(4)?-1:c.getLong(4);
          Item item=new Item(path,id,folder,size,date);
          if(result.put(path,item)!=null)throw new IOException("Caminho duplicado: "+path);
          if(result.size()>MAX_ITEMS)throw new IOException("Limite de "+MAX_ITEMS+" itens");
          if(folder)pending.addLast(item);
        }
      }catch(SecurityException e){throw new IOException("Sem permissao para "+parent.relative,e);}
    }
    return result;
  }
  private byte[] digest(Uri tree,String id)throws Exception{
    MessageDigest md=MessageDigest.getInstance("SHA-256");
    Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);
    try(InputStream in=activity.getContentResolver().openInputStream(doc)){
      if(in==null)throw new IOException("Nao foi possivel abrir documento");
      byte[] buf=new byte[32768];long bytes=0;int n;
      while((n=in.read(buf))!=-1){checkCancel();bytes+=n;if(bytes>MAX_HASH_BYTES)throw new IOException("Arquivo acima de 16 MiB: "+id);md.update(buf,0,n);}
    }
    return md.digest();
  }
  private void checkCancel()throws IOException{if(cancel)throw new IOException("Cancelado pelo usuario");}
  private void progress(){
    new AlertDialog.Builder(activity).setTitle("Comparacao 3.3")
      .setMessage((running?"Em execucao":"Parado")+"\nEtapa: "+stage+"\nItens lidos: "+scanned)
      .setPositiveButton("Fechar",null)
      .setNegativeButton("Cancelar analise",(d,w)->{cancel=true;toast("Cancelamento solicitado");}).show();
  }
  private void copy(){ClipboardManager cm=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Comparacao pastas",lastReport));toast("Relatorio copiado");}
  private void show(String title,String s){new AlertDialog.Builder(activity).setTitle(title).setMessage(s).setPositiveButton("Fechar",null).show();}
  private void toast(String msg){Toast.makeText(activity,msg,Toast.LENGTH_LONG).show();}
}
