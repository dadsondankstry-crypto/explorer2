package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ferramentas adicionadas à versão 3.7. Trabalham somente com SAF autorizado. */
final class Tools37 {
  private final MainActivity a;
  private volatile boolean cancel=false;
  Tools37(MainActivity host){a=host;}
  void close(){cancel=true;}

  void menu(){
    String[] opts={"Pesquisar nomes dentro de ZIP (sem extrair)",
      "Exportar inventário CSV da pasta e subpastas",
      "Verificar estrutura básica de projeto PWA",
      "Aparência: tema e tamanho de texto",
      "Sobre esta atualização"};
    new AlertDialog.Builder(a).setTitle("Ferramentas 3.7")
      .setItems(opts,(dlg,idx)->{switch(idx){
        case 0:searchZip();break;
        case 1:chooseCsv();break;
        case 2:auditPwa();break;
        case 3:appearance();break;
        case 4:information();break;
      }}).setNegativeButton("Fechar",null).show();
  }
  void information(){new AlertDialog.Builder(a).setTitle("Explorador ZIP 3.7")
    .setMessage("Revisão do catálogo de 300 propostas. As contagens representam presença de código, não testes concluídos.\n\nNovidades: buscar nomes no ZIP; exportar inventário recursivo CSV; auditoria básica PWA; tema automático/claro/escuro; tamanho de texto; novo ícone adaptativo.\n\nOs arquivos originais não são alterados por estas ferramentas de análise.")
    .setPositiveButton("OK",null).show();}
  void searchZip(){
    if(!a.inZip || a.zipFile==null){notice("Abra primeiro um ZIP sem extrair");return;}
    EditText input=new EditText(a); input.setSingleLine(true);input.setHint("Ex.: .github, index.html");
    new AlertDialog.Builder(a).setTitle("Pesquisar por nome dentro do ZIP")
      .setView(input).setNegativeButton("Cancelar",null)
      .setPositiveButton("Pesquisar",(d,w)->{
        String term=input.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(term.isEmpty()){notice("Informe o nome ou parte do nome");return;}
        int count=0;StringBuilder matches=new StringBuilder();
        for(MainActivity.ZItem entry:a.zipItems){
          if(!a.hidden && hiddenPath(entry.name))continue;
          if(!entry.name.toLowerCase(Locale.ROOT).contains(term))continue;
          count++;if(matches.length()<48000)matches.append(entry.folder?"[PASTA] ":"[ARQUIVO] ")
            .append(entry.name).append("\n");
        }
        a.showReport("ZIP: "+count+" ocorrência(s)","Busca por: "+term+"\nArquivo: "+a.zipName+
          "\nNenhuma extração realizada.\n\n"+matches+
          (count>0 && matches.length()>=48000?"\n[Lista truncada]":""));
      }).show();
  }
  private boolean hiddenPath(String name){
    for(String part:name.replace('\\','/').split("/"))if(part.startsWith("."))return true;
    return false;
  }
  void chooseCsv(){
    if(a.inZip || a.root==null || a.docId==null || a.busy){notice("Abra uma pasta normal primeiro");return;}
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.setType("text/csv");intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(Intent.EXTRA_TITLE,"inventario_explorador37.csv");
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try{a.startActivityForResult(intent,MainActivity.INVENTORY37_CSV);}
    catch(Exception e){notice("Não foi possível selecionar destino: "+e.getMessage());}
  }
  void csvDestination(Uri output){
    if(a.root==null||a.docId==null||a.busy){notice("Pasta indisponível");return;}
    Uri tree=a.root;String start=a.docId;boolean includeHidden=a.hidden;
    a.busy=true;cancel=false;a.status.setText("Exportando inventário CSV...");
    a.worker.execute(()->{
      int count=0;String error=null;boolean limited=false;
      try(OutputStream dest=a.getContentResolver().openOutputStream(output,"w")){
        if(dest==null)throw new IOException("Não foi possível abrir destino");
        OutputStreamWriter writer=new OutputStreamWriter(dest,StandardCharsets.UTF_8);
        writer.write("caminho_relativo,nome,tipo,tamanho_bytes,modificado_ms,mime,oculto\r\n");
        ArrayDeque<Node> queue=new ArrayDeque<>(); queue.add(new Node(start,"",0));
        HashSet<String> scanned=new HashSet<>();
        while(!queue.isEmpty() && !cancel && count<3000){
          Node node=queue.removeFirst();if(!scanned.add(node.id))continue;
          Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,node.id);
          String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
          try(Cursor c=a.getContentResolver().query(children,projection,null,null,null)){
            if(c==null)continue;
            while(c.moveToNext()&&count<3000){
              String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
              if(id==null||name==null||(!includeHidden&&name.startsWith(".")))continue;
              boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
              long size=c.isNull(3)?-1:c.getLong(3),modified=c.isNull(4)?-1:c.getLong(4);
              String relative=node.path+name+(folder?"/":"");
              writer.write(csv(relative)+","+csv(name)+","+csv(folder?"pasta":"arquivo")+","+
                (size<0?"":Long.toString(size))+","+(modified<0?"":Long.toString(modified))+","+
                csv(mime==null?"":mime)+","+(name.startsWith(".")?"sim":"não")+"\r\n");
              count++;
              if(folder && node.depth<24)queue.add(new Node(id,relative,node.depth+1));
            }
          }
          if(count%100==0)writer.flush();
        }
        limited=count>=3000||!queue.isEmpty();writer.flush();
      }catch(Exception e){error=e.getMessage();}
      final String problem=error;final int n=count;final boolean cap=limited;
      a.runOnUiThread(()->{a.busy=false;a.refresh();
        new AlertDialog.Builder(a).setTitle(problem==null?"Inventário exportado":"Exportação incompleta")
          .setMessage(n+" itens registrados em CSV. "+(cap?"Limite de 3000 itens/profundidade 24; resultado pode estar incompleto. ":"")+
            (cancel?"Cancelado. ":"")+(problem==null?"":"Erro: "+problem)+
            "\n\nApenas leitura dos arquivos de origem. CSV exige cuidado ao abrir em planilhas.")
          .setPositiveButton("OK",null).show();
      });
    });
  }
  private static final class Node {String id,path;int depth;Node(String a,String b,int c){id=a;path=b;depth=c;}}
  private String csv(String raw){
    // Evita que nomes arbitrários virem fórmulas ao abrir o CSV em uma planilha.
    String s=raw==null?"":raw;
    if(!s.isEmpty()&&"=+-@".indexOf(s.charAt(0))>=0)s="'"+s;
    return "\""+s.replace("\"","\"\"").replace("\r"," ").replace("\n"," ")+"\"";
  }
  void auditPwa(){
    if(a.inZip || a.root==null || a.docId==null || a.busy){notice("Abra uma pasta comum do projeto");return;}
    Uri tree=a.root;String parentId=a.docId;
    a.busy=true;a.status.setText("Verificando estrutura PWA...");
    a.worker.execute(()->{
      StringBuilder report=new StringBuilder("ANÁLISE ESTÁTICA PWA — pasta selecionada\n\n");
      try{
        TreeMap<String,String> names=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Uri childUri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        try(Cursor c=a.getContentResolver().query(childUri,
           new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
          if(c==null)throw new IOException("Pasta não acessível");
          while(c.moveToNext())if(c.getString(1)!=null)names.put(c.getString(1),c.getString(0));
        }
        boolean index=names.containsKey("index.html");
        String manifest=names.containsKey("manifest.webmanifest")?"manifest.webmanifest":
          names.containsKey("manifest.json")?"manifest.json":null;
        String sw=names.containsKey("sw.js")?"sw.js":names.containsKey("service-worker.js")?"service-worker.js":null;
        report.append(index?"OK ":"FALTA ").append("index.html\n");
        report.append(manifest!=null?"OK ":"FALTA ").append("manifest.webmanifest ou manifest.json\n");
        report.append(sw!=null?"OK ":"FALTA ").append("sw.js ou service-worker.js\n");
        if(manifest!=null){
          try{
            String raw=readSmall(tree,names.get(manifest),384*1024);
            JSONObject obj=new JSONObject(raw);
            report.append("\nMANIFEST: ").append(manifest).append("\n");
            report.append(obj.has("name")||obj.has("short_name")?"OK":"FALTA").append(" nome\n");
            report.append(obj.optString("start_url","").isEmpty()?"FALTA":"OK").append(" start_url\n");
            report.append(obj.optString("display","").isEmpty()?"AVISO":"OK").append(" display\n");
            JSONArray icons=obj.optJSONArray("icons");
            report.append(icons!=null&&icons.length()>0?"OK ":"AVISO ").append("icons declarados\n");
            if(icons!=null){for(int i=0;i<Math.min(icons.length(),12);i++){
              JSONObject icon=icons.optJSONObject(i);if(icon!=null){String src=icon.optString("src","");
                if(src.indexOf('/')<0&&!src.isEmpty()&&!names.containsKey(src))
                   report.append("AVISO ícone ausente na raiz: ").append(src).append("\n");}}
            }
          }catch(Exception err){report.append("AVISO: manifest não pôde ser analisado: ").append(err.getMessage()).append("\n");}
        }
        if(index){
          try{
            String html=readSmall(tree,names.get("index.html"),512*1024).toLowerCase(Locale.ROOT);
            report.append("\nindex.html: ");
            report.append(html.contains("rel=\"manifest\"")||html.contains("rel='manifest'")?"há link rel=manifest":"não foi detectado link rel=manifest").append("\n");
            report.append(html.contains("serviceworker")?"Há referência a Service Worker":"Nenhuma referência a Service Worker detectada").append("\n");
          }catch(Exception ex){report.append("index.html não verificado: ").append(ex.getMessage()).append("\n");}
        }
        report.append("\nVerificação informativa: não executa a PWA, não verifica cache offline, caminhos relativos completos, registro do service worker nem valida JSON schema.");
      }catch(Exception e){report.append("Erro: ").append(e.getMessage());}
      final String text=report.toString();
      a.runOnUiThread(()->{a.busy=false;a.refresh();a.showReport("Verificação PWA",text);});
    });
  }
  private String readSmall(Uri tree,String id,int limit)throws IOException {
    Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,id);
    try(InputStream in=a.getContentResolver().openInputStream(document)){
      if(in==null)throw new IOException("Leitura negada");
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
      while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Arquivo maior que limite de análise");out.write(b,0,n);}
      return out.toString("UTF-8");
    }
  }
  void appearance(){
    String[] opts={"Tema automático (sistema)","Tema claro","Tema escuro",
      "Texto compacto","Texto normal","Texto grande"};
    new AlertDialog.Builder(a).setTitle("Aparência do Explorador ZIP")
      .setItems(opts,(d,i)->{
        if(i<3)a.prefs.edit().putString("mode37",i==0?"sistema":i==1?"claro":"escuro").apply();
        else a.prefs.edit().putInt("font37",i==3?12:i==4?14:16).apply();
        a.recreate();
      }).setNegativeButton("Cancelar",null).show();
  }
  private void notice(String s){Toast.makeText(a,s,Toast.LENGTH_LONG).show();}
}
