package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Compressão/descompressão de um arquivo GZIP na mesma pasta SAF, sem substituir o original. */
final class Gzip36 {
  private static final long LIMIT=256L*1024*1024;
  private final MainActivity a;
  private volatile boolean running=false;
  Gzip36(MainActivity a){this.a=a;}
  private void notifyUser(String s){a.runOnUiThread(()->Toast.makeText(a,s,Toast.LENGTH_LONG).show());}
  void menu(){
    new AlertDialog.Builder(a).setTitle("Ferramentas GZIP 3.6")
      .setItems(new String[]{"Criar .gz a partir de UM arquivo selecionado","Extrair UM .gz na pasta atual","Sobre o formato GZIP"},(d,i)->{
        if(i==0)prepare(false);else if(i==1)prepare(true);else
          new AlertDialog.Builder(a).setTitle("GZIP")
            .setMessage("GZIP compacta um fluxo/arquivo, não uma pasta inteira. Um arquivo .tar.gz será descompactado para .tar; esta ferramenta ainda não abre o conteúdo TAR. Limite de 256 MiB por operação. Os originais não são apagados.")
            .setPositiveButton("OK",null).show();
      }).show();
  }
  private void prepare(boolean decompress){
    if(running||a.busy){notifyUser("Termine a operação atual");return;}
    if(a.root==null||a.inZip||a.docId==null){notifyUser("Abra uma pasta normal");return;}
    ArrayList<MainActivity.Row> rows=a.selectedFiles();
    if(rows.size()!=1||rows.get(0).folder){notifyUser("Selecione exatamente um arquivo, com toque longo");return;}
    MainActivity.Row file=rows.get(0);
    boolean gz=file.name.toLowerCase(Locale.ROOT).endsWith(".gz");
    if(decompress&&!gz){notifyUser("Selecione um arquivo com extensão .gz");return;}
    String name=decompress?file.name.substring(0,file.name.length()-3):file.name+".gz";
    if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.contains("\u0000")){
      notifyUser("Nome do arquivo inválido");return;
    }
    final Uri tree=a.root;final String currentId=a.docId;
    new AlertDialog.Builder(a).setTitle(decompress?"Extrair GZIP":"Criar GZIP")
      .setMessage("Origem: "+file.name+"\nNovo arquivo: "+name+"\nNa mesma pasta, sem substituir o original.\nLimite: 256 MiB de dados.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Confirmar",(d,w)->run(tree,currentId,file,name,decompress)).show();
  }
  private boolean contains(Uri tree,String parent,String name)throws Exception{
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
    try(Cursor c=a.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c==null)throw new IOException("Não foi possível conferir nomes existentes");
      while(c.moveToNext())if(name.equalsIgnoreCase(c.getString(0)))return true;
    }
    return false;
  }
  private void run(Uri tree,String parent,MainActivity.Row row,String targetName,boolean decompress){
    if(running)return;running=true;
    a.worker.execute(()->{
      Uri created=null;String error=null;long count=0;
      try{
        if(contains(tree,parent,targetName))throw new IOException("O nome "+targetName+" já existe. Nada foi sobrescrito.");
        Uri source=DocumentsContract.buildDocumentUriUsingTree(tree,row.id);
        Uri targetParent=DocumentsContract.buildDocumentUriUsingTree(tree,parent);
        created=DocumentsContract.createDocument(a.getContentResolver(),targetParent,"application/octet-stream",targetName);
        if(created==null)throw new IOException("Pasta não permite criar arquivos");
        byte[] buffer=new byte[32768];int n;
        if(decompress){
          try(InputStream sourceStream=a.getContentResolver().openInputStream(source);
              GZIPInputStream input=new GZIPInputStream(new BufferedInputStream(sourceStream));
              OutputStream out=a.getContentResolver().openOutputStream(created,"wt")){
            if(out==null)throw new IOException("Sem escrita no destino");
            while((n=input.read(buffer))!=-1){count+=n;if(count>LIMIT)throw new IOException("Arquivo excede 256 MiB");out.write(buffer,0,n);}
          }
        }else{
          try(InputStream in=a.getContentResolver().openInputStream(source);
              OutputStream raw=a.getContentResolver().openOutputStream(created,"wt");
              GZIPOutputStream out=new GZIPOutputStream(new BufferedOutputStream(raw))){
            while((n=in.read(buffer))!=-1){count+=n;if(count>LIMIT)throw new IOException("Arquivo excede 256 MiB");out.write(buffer,0,n);}
          }
        }
      }catch(Exception e){error=e.getMessage();if(created!=null)try{DocumentsContract.deleteDocument(a.getContentResolver(),created);}catch(Exception ignored){}}
      final String problem=error;final long amount=count;
      a.runOnUiThread(()->{running=false;if(problem==null){a.addLog("GZIP: "+row.name+" → "+targetName);a.refresh();new AlertDialog.Builder(a).setTitle("GZIP concluído").setMessage("Arquivo criado: "+targetName+"\nDados processados: "+amount+" bytes.\nOriginal preservado.")
          .setPositiveButton("OK",null).show();}
        else notifyUser("Falha GZIP: "+problem+". Arquivo incompleto removido quando possível.");});
    });
  }
}
