package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import org.json.*;

/** Ferramentas de produtividade 3.6. Nenhuma operação altera os arquivos pesquisados. */
final class Research36 {
  private static final int MAX_NODES=2200, MAX_DEPTH=18, MAX_FILE_BYTES=512*1024, MAX_TOTAL_BYTES=18*1024*1024;
  private static final int MAX_MATCHES=160;
  private final MainActivity app;
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancel=new AtomicBoolean(false);
  private volatile boolean working=false;
  private volatile int examined=0, searched=0, found=0;
  private volatile String current="";
  private volatile String lastReport="";
  Research36(MainActivity activity){app=activity;}
  void close(){cancel.set(true);executor.shutdownNow();}
  private void message(String title,String text){app.runOnUiThread(()->new AlertDialog.Builder(app).setTitle(title).setMessage(text).setPositiveButton("Fechar",null).show());}
  private void notice(String text){app.runOnUiThread(()->Toast.makeText(app,text,Toast.LENGTH_LONG).show());}
  private void ask(String title,String hint,java.util.function.Consumer<String> done){
    EditText e=new EditText(app);e.setSingleLine(true);e.setHint(hint);e.setPadding(20,16,20,16);
    new AlertDialog.Builder(app).setTitle(title).setView(e).setNegativeButton("Cancelar",null)
      .setPositiveButton("Continuar",(d,b)->done.accept(e.getText().toString().trim())).show();
  }
  void menu(){
    String[] options={"Pesquisar texto em arquivos de subpastas (sem modificar)",
      "Selecionar nomes por expressão regular (pasta atual)","Salvar uma busca por conteúdo", 
      "Executar busca salva", "Limpar buscas salvas", "Situação/cancelar pesquisa", 
      "Copiar último relatório", "Exportar preferências (JSON)","Importar preferências (JSON)",
      "Explicar limites de acesso", "Consultar auditoria das 300 funções"};
    new AlertDialog.Builder(app).setTitle("Pesquisa e ajustes · 3.6")
      .setItems(options,(d,i)->{
        switch(i){
          case 0:ask("Procurar texto (UTF-8)","ex.: TODO ou versionName",q->search(q));break;
          case 1:ask("Selecionar nomes com regex","ex.: \\.(html|js|css)$",q->selectRegex(q));break;
          case 2:ask("Salvar termo de pesquisa","ex.: projeto",this::saveQuery);break;
          case 3:searchSaved();break;
          case 4:clearSaved();break;
          case 5:progress();break;
          case 6:copyReport();break;
          case 7:exportSettings();break;
          case 8:importSettings();break;
          case 9:limits();break;
          case 10:catalog();break;
        }
      }).show();
  }
  private void check()throws IOException{if(cancel.get()||Thread.currentThread().isInterrupted())throw new IOException("Pesquisa cancelada");}
  private boolean isText(String name,String mime){
    String lower=name.toLowerCase(Locale.ROOT);
    if(mime!=null&&(mime.startsWith("text/")||mime.equals("application/json")||mime.equals("application/xml")))return true;
    return lower.matches(".*\\.(txt|md|html?|css|js|mjs|ts|jsx|tsx|json|xml|yml|yaml|gradle|properties|ini|csv|log|java|kt|py|sh|bat|c|h|cpp|sql|svg|gitignore)$")
      || lower.equals(".gitignore")||lower.equals("dockerfile")||lower.equals("makefile");
  }
  private static final class Item{
    final String id,path;final int depth;
    Item(String i,String p,int d){id=i;path=p;depth=d;}
  }
  private String runSearch(Uri tree,String term)throws Exception{
    int bytesRead=0,nodes=0,tooBig=0,skipped=0;StringBuilder report=new StringBuilder();
    report.append("PESQUISA RECURSIVA 3.6 — SOMENTE LEITURA\nTermo: ").append(term).append("\n");
    report.append("Limites: ").append(MAX_NODES).append(" entradas; 18 níveis; 512 KiB/arquivo; 18 MiB no total.\n\n");
    ArrayDeque<Item> todo=new ArrayDeque<>();todo.add(new Item(DocumentsContract.getTreeDocumentId(tree),"",0));
    String query=term.toLowerCase(Locale.ROOT);
    int hits=0;
    while(!todo.isEmpty()){
      check();Item parent=todo.removeFirst();
      Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent.id);
      String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
      try(Cursor c=app.getContentResolver().query(children,projection,null,null,null)){
        if(c==null)throw new IOException("Não foi possível ler: "+parent.path);
        while(c.moveToNext()){
          check();if(++nodes>MAX_NODES){report.append("\n[LIMITE DE ITENS ALCANÇADO]\n");todo.clear();break;}
          String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
          if(name==null||name.isEmpty()||name.contains("/")||name.contains("\\"))continue;
          if(!app.hidden&&name.startsWith("."))continue;
          String path=parent.path.isEmpty()?name:parent.path+"/"+name;
          if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
            if(parent.depth<MAX_DEPTH)todo.add(new Item(id,path,parent.depth+1));else skipped++;
            continue;
          }
          if(!isText(name,mime)){skipped++;continue;}
          long size=c.isNull(3)?-1:c.getLong(3);
          if(size>MAX_FILE_BYTES){tooBig++;continue;}
          Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
          ByteArrayOutputStream b=new ByteArrayOutputStream();
          try(InputStream stream=app.getContentResolver().openInputStream(uri)){
            if(stream==null){skipped++;continue;}
            byte[] buffer=new byte[8192];int n;
            while((n=stream.read(buffer))!=-1){check();if(b.size()+n>MAX_FILE_BYTES){tooBig++;b.reset();break;}
              bytesRead+=n;if(bytesRead>MAX_TOTAL_BYTES){report.append("\n[LIMITE DE LEITURA ALCANÇADO]\n");todo.clear();b.reset();break;}
              b.write(buffer,0,n);
            }
          }catch(SecurityException|FileNotFoundException ex){skipped++;continue;}
          if(b.size()==0)continue;
          searched++;current=path;
          String[] lines=new String(b.toByteArray(),StandardCharsets.UTF_8).split("\\r?\\n",-1);
          for(int line=0;line<lines.length;line++){
            if(lines[line].toLowerCase(Locale.ROOT).contains(query)){
              found++;hits++;
              if(hits<=MAX_MATCHES)report.append(path).append(" : linha ").append(line+1).append('\n');
            }
          }
        }
      }
    }
    report.append("\nItens inspecionados: ").append(nodes).append("\nArquivos de texto lidos: ").append(searched)
      .append("\nLinhas encontradas: ").append(found).append("\nArquivos acima do limite: ").append(tooBig)
      .append("\nItens ignorados: ").append(skipped).append('\n');
    if(hits>MAX_MATCHES)report.append("[Relatório limitado às primeiras ").append(MAX_MATCHES).append(" correspondências]\n");
    report.append("\nNenhum arquivo foi modificado. Não é uma pesquisa em todo o aparelho: somente na pasta autorizada.");
    return report.toString();
  }
  private void search(String q){
    if(q.isEmpty()){notice("Informe uma palavra ou trecho");return;}
    if(working){notice("Já existe uma pesquisa em andamento");return;}
    if(app.root==null||app.inZip){notice("Abra uma pasta normal para pesquisar conteúdo");return;}
    final Uri tree=app.root;working=true;cancel.set(false);examined=0;searched=0;found=0;current="Preparando pesquisa";
    executor.execute(()->{
      String report;
      try{report=runSearch(tree,q);}catch(Exception e){report="Pesquisa encerrada: "+e.getMessage()+"\nArquivos lidos: "+searched+"; correspondências: "+found;}
      lastReport=report;working=false;message("Resultado da pesquisa",report.length()>18000?report.substring(0,18000)+"\n[Copie relatório para os detalhes]":report);
    });notice("Pesquisa iniciada. Consulte 'Situação/cancelar' para acompanhar.");
  }
  private void selectRegex(String expr){
    if(app.root==null||app.inZip){notice("Abra uma pasta normal");return;}
    try{
      Pattern regex=Pattern.compile(expr,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
      int matches=0;for(MainActivity.Row row:app.rows)if(regex.matcher(row.name).find()){app.selected.add(row.id);matches++;}
      app.render(matches+" itens correspondem à expressão (somente selecionados)");
    }catch(PatternSyntaxException ex){notice("Expressão inválida: "+ex.getDescription());}
  }
  private JSONArray saved(){try{return new JSONArray(app.prefs.getString("search36_terms","[]"));}catch(Exception e){return new JSONArray();}}
  private void saveQuery(String q){
    if(q.isEmpty()){notice("Informe um termo");return;}
    JSONArray old=saved();JSONArray out=new JSONArray();out.put(q);for(int k=0;k<old.length()&&out.length()<15;k++){
      String item=old.optString(k,"");if(!q.equalsIgnoreCase(item)&&!item.isEmpty())out.put(item);
    }
    app.prefs.edit().putString("search36_terms",out.toString()).apply();notice("Busca salva no aplicativo");
  }
  private void searchSaved(){JSONArray j=saved();if(j.length()==0){notice("Nenhuma busca salva");return;}
    String[] terms=new String[j.length()];for(int i=0;i<terms.length;i++)terms[i]=j.optString(i);
    new AlertDialog.Builder(app).setTitle("Buscas salvas").setItems(terms,(d,w)->search(terms[w])).show();
  }
  private void clearSaved(){new AlertDialog.Builder(app).setTitle("Apagar buscas salvas?")
    .setPositiveButton("Apagar",(d,w)->{app.prefs.edit().remove("search36_terms").apply();notice("Buscas removidas");})
    .setNegativeButton("Cancelar",null).show();}
  private void progress(){
    String s=working?"Em andamento\nArquivo: "+current+"\nTextos lidos: "+searched+"\nCorrespondências: "+found:
      "Nenhuma pesquisa em andamento.\nÚltimo relatório: "+(lastReport.isEmpty()?"não disponível":"disponível para copiar");
    new AlertDialog.Builder(app).setTitle("Pesquisa 3.6").setMessage(s)
      .setPositiveButton("Fechar",null).setNegativeButton(working?"Cancelar pesquisa":"OK",(d,w)->{if(working)cancel.set(true);}).show();
  }
  private void copyReport(){if(lastReport.isEmpty()){notice("Execute uma pesquisa primeiro");return;}
    ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("Pesquisa 3.6",lastReport));notice("Relatório copiado");}
  private JSONObject settings(){
    JSONObject j=new JSONObject();try{
      j.put("format","exploradorzip-preferencias-1");
      j.put("hidden",app.prefs.getBoolean("hidden",true));
      j.put("sort",app.prefs.getString("sort","name"));
      j.put("reverse",app.prefs.getBoolean("reverse",false));
      j.put("compressionLevel",app.prefs.getInt("compressionLevel",6));
      j.put("tools_open",app.prefs.getBoolean("tools_open",false));
      j.put("search36_terms",saved());
    }catch(Exception ignored){}return j;
  }
  private void exportSettings(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
    i.putExtra(Intent.EXTRA_TITLE,"ExploradorZIP_preferencias.json");app.startActivityForResult(i,MainActivity.CONFIG36_EXPORT);}
  private void importSettings(){new AlertDialog.Builder(app).setTitle("Importar preferências")
    .setMessage("Isso substitui suas opções de ordenação, ocultos, compactação e buscas salvas. Não altera arquivos nem restaura permissões de pastas.")
    .setPositiveButton("Escolher JSON",(d,w)->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");app.startActivityForResult(i,MainActivity.CONFIG36_IMPORT);})
    .setNegativeButton("Cancelar",null).show();}
  void result(int code,Uri uri){
    if(code==MainActivity.CONFIG36_EXPORT){
      try(OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")){
        if(out==null)throw new IOException("Destino indisponível");out.write(settings().toString(2).getBytes(StandardCharsets.UTF_8));
        notice("Preferências exportadas");
      }catch(Exception e){notice("Falha na exportação: "+e.getMessage());}
    }else if(code==MainActivity.CONFIG36_IMPORT){
      try(InputStream in=app.getContentResolver().openInputStream(uri)){
        if(in==null)throw new IOException("Arquivo indisponível");byte[] data=new byte[32*1024];int count=0,n;
        while((n=in.read(data,count,data.length-count))>0){count+=n;if(count==data.length)break;}
        JSONObject j=new JSONObject(new String(data,0,count,StandardCharsets.UTF_8));
        if(!"exploradorzip-preferencias-1".equals(j.optString("format")))throw new IOException("Formato de preferências desconhecido");
        String mode=j.optString("sort","name");if(!Arrays.asList("name","size","date","type").contains(mode))mode="name";
        int level=j.optInt("compressionLevel",6);level=Math.max(0,Math.min(9,level));
        JSONArray terms=j.optJSONArray("search36_terms");
        app.prefs.edit().putBoolean("hidden",j.optBoolean("hidden",true))
          .putString("sort",mode).putBoolean("reverse",j.optBoolean("reverse",false))
          .putInt("compressionLevel",level).putBoolean("tools_open",j.optBoolean("tools_open",false))
          .putString("search36_terms",terms==null?"[]":terms.toString()).apply();
        app.hidden=j.optBoolean("hidden",true);app.sortMode=mode;app.reverse=j.optBoolean("reverse",false);app.compressionLevel=level;
        app.refresh();notice("Preferências importadas. Reabra o aplicativo para atualizar todos os controles.");
      }catch(Exception e){notice("Não foi possível importar: "+e.getMessage());}
    }
  }
  private void limits(){message("Limites do Android e do projeto",
    "Pesquisa e operações somente nas pastas autorizadas. Android 11+ restringe raízes e Android/data. " +
    "Alguns provedores não aceitam MOVE/COPY, não fornecem datas de criação e podem alterar a data de modificação. " +
    "A ferramenta de texto lê no máximo 512 KiB por arquivo e 18 MiB por execução; não varre binários. " +
    "As opções oferecidas no menu não equivalem a recursos compilados e testados em todos os aparelhos.");}
  private void catalog(){
    try(InputStream in=app.getAssets().open("RESUMO_AUDITORIA.txt")){
      ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
      while((n=in.read(buf))>0&&b.size()<8000)b.write(buf,0,n);
      message("Auditoria · catálogo original",b.toString("UTF-8"));
    }catch(Exception ex){message("Auditoria","Consulte os relatórios CSV e HTML fornecidos com o projeto.");}
  }
}
