package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Ferramentas 2.7: leituras seguras do SAF, checagem de integridade e auditoria.
 * Nenhuma destas operações renomeia, apaga ou move arquivos.
 */
final class AdvancedTools {
  private final MainActivity a;
  private final AtomicBoolean stop = new AtomicBoolean(false);
  private volatile int visited = 0;
  private volatile boolean auditing = false;
  private String lastTitle = "", lastText = "";
  private static final int MAX_ENTRIES=3000, MAX_DEPTH=18;
  private static final long HASH_LIMIT=256L*1024*1024;

  AdvancedTools(MainActivity activity){a=activity;}
  void cancel(){stop.set(true);}

  void showMenu(){
    String[] sections={
      "🔐 Integridade: hashes, comparação, checagem",
      "🔎 Auditorias recursivas: pastas e armazenamento",
      "📋 Histórico, exportação e permissões",
      "✖ Cancelar análise em andamento",
      "ℹ Limites e cuidados desta versão"
    };
    new AlertDialog.Builder(a).setTitle("Ferramentas avançadas · v2.7")
      .setItems(sections,(d,which)->{
        switch(which){case 0:integrityMenu();break;case 1:auditMenu();break;
          case 2:recordsMenu();break;case 3:cancelScan();break;default:help();}
      }).setNegativeButton("Fechar",null).show();
  }

  void cancelScan(){
    if(!auditing){a.fail("Não existe auditoria 2.7 em andamento.");return;}
    stop.set(true);a.progressText.setText("Cancelamento solicitado. Finalizando a leitura atual...");
  }

  void help(){
    a.showReport("Escopo e segurança · 2.7",
      "• As auditorias são apenas de leitura: não movem, renomeiam nem apagam arquivos.\n"+
      "• São analisadas somente as pastas que o Android autorizou pelo seletor do sistema (SAF).\n"+
      "• A busca recursiva tem limite de 3.000 entradas e 18 níveis; ultrapassar o limite produz aviso, NÃO uma contagem completa.\n"+
      "• A data exibida é a da última modificação, quando disponível. Não equivale à criação.\n"+
      "• Hashes permitem comparar integridade, mas não atestam que um arquivo seja seguro. SHA-1 e MD5 são legados, NÃO recomendados para segurança.\n"+
      "• Leitura/hash limitada a 256 MB por arquivo. Duplicados recursivos analisam até 60 candidatos pequenos.\n"+
      "• Algumas pastas e metadados dependem do provedor de documentos.\n"+
      "• Cancelamento é cooperativo, pode levar alguns instantes; não afeta arquivos originais.\n"+
      "• Os relatórios permanecem na memória até você fechar o aplicativo ou exportá-los.\n"+
      "• Não há limpeza, lixeira, exclusão permanente nem recuperação automática nesta seção.");
  }

  private interface CheckedTask { String run() throws Exception; }
  private void work(String title, CheckedTask task){
    if(a.busy){a.fail("Já existe operação em andamento.");return;}
    a.busy=true;auditing=true;stop.set(false);visited=0;
    a.status.setText("Processando: "+title);a.progressText.setText("Iniciando análise...");
    a.worker.execute(()->{
      String result=null,problem=null;
      try{result=task.run();}catch(Exception e){problem=e.getMessage();}
      final String output=result,error=problem;
      a.runOnUiThread(()->{
        a.busy=false;auditing=false;a.progressText.setText("");a.render(error==null?"Análise concluída":("Análise interrompida: "+error));
        if(error==null)report(title,output);
        else new AlertDialog.Builder(a).setTitle("Análise interrompida").setMessage(error+"\n\nNenhum arquivo foi modificado.")
          .setPositiveButton("OK",null).show();
      });
    });
  }
  private void report(String title,String s){
    lastTitle=title;lastText=s==null?"":s;
    a.showReport(title,lastText);
  }
  private void checkCancel()throws IOException{if(stop.get())throw new IOException("Cancelada pelo usuário");}
  private void progress(String phase,int n){
    visited=n;if(n%40==0){final int at=n;a.runOnUiThread(()->a.progressText.setText(phase+" · "+at+" itens"));}
  }
  private List<MainActivity.Row> filesSelected(){
    if(a.inZip||a.root==null){a.fail("Abra uma pasta normal, não um arquivo ZIP.");return null;}
    ArrayList<MainActivity.Row> files=new ArrayList<>();
    for(MainActivity.Row r:a.selectedFiles())if(!r.folder)files.add(r);
    if(files.isEmpty())a.fail("Marque um ou mais arquivos com toque longo.");
    return files.isEmpty()?null:files;
  }
  private void one(String title,FileAction action){
    List<MainActivity.Row> items=filesSelected();if(items==null)return;
    if(items.size()!=1){a.fail("Selecione exatamente 1 arquivo.");return;}
    MainActivity.Row r=items.get(0);
    work(title,()->action.run(r));
  }
  private interface FileAction{String run(MainActivity.Row r)throws Exception;}
  private Uri fileUri(Uri tree,String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
  private byte[] checksum(Uri uri,String algorithm)throws Exception{
    MessageDigest dig=MessageDigest.getInstance(algorithm);
    try(InputStream input=a.getContentResolver().openInputStream(uri)){
      if(input==null)throw new IOException("O provedor não permitiu ler o arquivo");
      byte[] buf=new byte[32768];int n;long bytes=0;
      while((n=input.read(buf))!=-1){checkCancel();bytes+=n;
        if(bytes>HASH_LIMIT)throw new IOException("Arquivo acima de 256 MB (limite da auditoria)");
        if(n>0)dig.update(buf,0,n);
      }
    }
    return dig.digest();
  }
  private String checksumHex(Uri uri,String algo)throws Exception{return hex(checksum(uri,algo));}
  private static String hex(byte[] dig){
    StringBuilder sb=new StringBuilder(dig.length*2);
    for(byte b:dig)sb.append(String.format(Locale.US,"%02x",b&255));return sb.toString();
  }
  private long crc32(Uri uri)throws Exception{
    CRC32 crc=new CRC32();
    try(InputStream input=a.getContentResolver().openInputStream(uri)){
      if(input==null)throw new IOException("Sem acesso ao arquivo");
      byte[] buf=new byte[32768];int n;long total=0;
      while((n=input.read(buf))!=-1){checkCancel();total+=n;
        if(total>HASH_LIMIT)throw new IOException("Limite de 256 MB");if(n>0)crc.update(buf,0,n);
      }
    }return crc.getValue();
  }
  private void hashOne(String algorithm){
    one(algorithm+" do arquivo",r->r.name+"\n"+algorithm+": "+checksumHex(fileUri(a.root,r.id),algorithm)
      +"\n\nComparação de conteúdo; não detecta malware.");
  }
  private void integrityMenu(){
    final String[] options={
      "01 · SHA-512 do arquivo selecionado",
      "02 · SHA-1 (legado; não usar para segurança)",
      "03 · MD5 (legado; não usar para segurança)",
      "04 · CRC32 do arquivo selecionado",
      "05 · Conferir SHA-256 esperado (64 caracteres)",
      "06 · Listar SHA-256 dos arquivos selecionados",
      "07 · Testar leitura dos arquivos selecionados",
      "08 · Comparar 2 arquivos byte a byte",
      "09 · Gerar ficha de integridade SHA-256 + SHA-512"
    };
    new AlertDialog.Builder(a).setTitle("Integridade · 9 ferramentas").setItems(options,(d,w)->{
      switch(w){case 0:hashOne("SHA-512");break;case 1:hashOne("SHA-1");break;
        case 2:hashOne("MD5");break;case 3:one("CRC32",r->
          r.name+"\nCRC32: "+Long.toHexString(crc32(fileUri(a.root,r.id))).toUpperCase(Locale.ROOT));break;
        case 4:verifyExpected();break;case 5:hashManifest();break;case 6:testReadable();break;
        case 7:compareBytes();break;case 8:integritySheet();break;
      }
    }).setNegativeButton("Voltar",(d,w)->showMenu()).show();
  }
  private void verifyExpected(){
    List<MainActivity.Row> items=filesSelected();if(items==null)return;
    if(items.size()!=1){a.fail("Selecione 1 arquivo para conferir o hash.");return;}
    MainActivity.Row r=items.get(0);
    a.prompt("SHA-256 esperado (64 caracteres hex)","",expected->{
      final String exp=expected.trim().toLowerCase(Locale.ROOT);
      if(!exp.matches("[0-9a-f]{64}")){a.fail("SHA-256 inválido: precisa de 64 caracteres hexadecimais.");return;}
      work("Conferir SHA-256",()->{
        String actual=checksumHex(fileUri(a.root,r.id),"SHA-256");
        boolean equal=MessageDigest.isEqual(actual.getBytes("US-ASCII"),exp.getBytes("US-ASCII"));
        return "Arquivo: "+r.name+"\nEsperado: "+exp+"\nObtido:  "+actual+"\n\n"+
          (equal?"CORRESPONDE ao hash informado.":"NÃO CORRESPONDE ao hash informado.");
      });
    });
  }
  private void hashManifest(){
    List<MainActivity.Row> input=filesSelected();if(input==null)return;
    final List<MainActivity.Row> selected=new ArrayList<>(input);
    if(selected.size()>80){a.fail("Selecione no máximo 80 arquivos.");return;}
    final Uri tree=a.root;
    work("Manifesto SHA-256 da seleção",()->{
      StringBuilder b=new StringBuilder("SHA-256 · arquivos selecionados\n\n");int n=0;
      for(MainActivity.Row r:selected){checkCancel();b.append(checksumHex(fileUri(tree,r.id),"SHA-256"))
        .append("  ").append(r.name).append('\n');progress("Hashes",++n);}
      return b.toString();
    });
  }
  private void integritySheet(){
    one("Ficha de integridade",r->{Uri u=fileUri(a.root,r.id);
      return "Arquivo: "+r.name+"\nTamanho informado: "+MainActivity.sizeString(r.size)
        +"\nÚltima modificação: "+MainActivity.dateString(r.modified)
        +"\nSHA-256: "+checksumHex(u,"SHA-256")
        +"\nSHA-512: "+checksumHex(u,"SHA-512")
        +"\nCRC32: "+Long.toHexString(crc32(u)).toUpperCase(Locale.ROOT);
    });
  }
  private void testReadable(){
    List<MainActivity.Row> input=filesSelected();if(input==null)return;
    final List<MainActivity.Row> selected=new ArrayList<>(input);
    if(selected.size()>100){a.fail("Limite: 100 arquivos selecionados");return;}
    final Uri tree=a.root;
    work("Testar leitura da seleção",()->{
      int pass=0,fail=0;StringBuilder b=new StringBuilder("Verifica somente se o arquivo pode ser aberto e lido até o primeiro byte.\n\n");
      for(MainActivity.Row r:selected){checkCancel();try(InputStream in=a.getContentResolver().openInputStream(fileUri(tree,r.id))){
        if(in==null)throw new IOException("sem stream");in.read();pass++;b.append("OK  ");
      }catch(Exception e){fail++;b.append("FALHA  ");}b.append(r.name).append('\n');progress("Aberturas",pass+fail);}
      return "Legíveis: "+pass+" / Falharam: "+fail+"\n\n"+b;
    });
  }
  private static int readChunk(InputStream in,byte[] buffer)throws IOException{
    int used=0;while(used<buffer.length){int n=in.read(buffer,used,buffer.length-used);
      if(n==-1)break;
      if(n==0){int c=in.read();if(c<0)break;buffer[used++]=(byte)c;}
      else used+=n;
    }
    return used==0?-1:used;
  }
  private InputStream inputFor(Uri u)throws Exception{
    InputStream stream=a.getContentResolver().openInputStream(u);
    if(stream==null)throw new IOException("Arquivo indisponível para leitura");
    return new java.io.BufferedInputStream(stream);
  }
  private void compareBytes(){
    List<MainActivity.Row> selected=filesSelected();if(selected==null)return;
    if(selected.size()!=2){a.fail("Marque exatamente dois arquivos.");return;}
    MainActivity.Row x=selected.get(0),y=selected.get(1);Uri tree=a.root;
    work("Comparação byte a byte",()->{
      if(x.size>=0&&y.size>=0&&x.size!=y.size)return "Diferentes: tamanho informado diverge.\n"+x.name+" · "+y.name;
      byte[] b1=new byte[32768],b2=new byte[32768];long count=0;
      try(InputStream i=inputFor(fileUri(tree,x.id));
          InputStream j=inputFor(fileUri(tree,y.id))){
        if(i==null||j==null)throw new IOException("Não foi possível abrir os dois arquivos");
        while(true){checkCancel();int n=readChunk(i,b1),m=readChunk(j,b2);
          if(n!=m)return "Diferentes: comprimentos divergem após "+count+" bytes.";
          if(n==-1)break;
          for(int k=0;k<n;k++)if(b1[k]!=b2[k])return "Diferentes no byte "+(count+k+1)+".";
          count+=n;if(count>HASH_LIMIT)throw new IOException("Limite de comparação: 256 MB");
        }
      }
      return "Conteúdo idêntico, "+count+" bytes conferidos.\n"+x.name+" = "+y.name;
    });
  }

  private static final class Node {
    final String id,name,path,mime;final boolean dir;final long size,modified;final int depth;
    Node(String id,String name,String path,String mime,boolean dir,long size,long modified,int depth){
      this.id=id;this.name=name;this.path=path;this.mime=mime;this.dir=dir;
      this.size=size;this.modified=modified;this.depth=depth;
    }
  }
  private static final class Folder {
    final String id,path;final int depth;
    Folder(String i,String p,int d){id=i;path=p;depth=d;}
  }
  private static final class Scan {
    final List<Node> nodes=new ArrayList<>();final Map<String,Integer> children=new HashMap<>();
    int dirs=0,files=0,unknownSize=0,unknownDate=0;long knownBytes=0;
    long started=System.currentTimeMillis();
  }
  private Scan scan(Uri tree,String startId)throws Exception{
    Scan s=new Scan();ArrayDeque<Folder> queue=new ArrayDeque<>();queue.add(new Folder(startId,"",0));
    Set<String> seen=new HashSet<>();
    String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    while(!queue.isEmpty()){
      checkCancel();Folder folder=queue.removeFirst();
      if(folder.depth>MAX_DEPTH)throw new IOException("Limite de profundidade ("+MAX_DEPTH+") excedido. Relatório cancelado para evitar números incompletos.");
      if(!seen.add(folder.id))continue;
      Uri uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folder.id);
      int children=0;
      try(Cursor c=a.getContentResolver().query(uri,columns,null,null,null)){
        if(c==null)throw new IOException("Sem permissão para listar "+folder.path);
        while(c.moveToNext()){
          checkCancel();String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
          if(id==null||name==null)continue;
          boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
          long bytes=c.isNull(3)?-1:c.getLong(3),date=c.isNull(4)?-1:c.getLong(4);
          String p=folder.path.isEmpty()?name:folder.path+"/"+name;
          Node n=new Node(id,name,p,mime,dir,bytes,date,folder.depth+1);s.nodes.add(n);
          if(s.nodes.size()>MAX_ENTRIES)throw new IOException("Limite de "+MAX_ENTRIES+" entradas excedido. Nenhum resultado parcial foi apresentado como completo.");
          children++;
          if(dir){s.dirs++;queue.add(new Folder(id,p,folder.depth+1));}
          else{s.files++;if(bytes<0)s.unknownSize++;else s.knownBytes+=bytes;
            if(date<=0)s.unknownDate++;}
          progress("Lendo árvore",s.nodes.size());
        }
      }
      s.children.put(folder.id,children);
    }
    return s;
  }
  private static String extension(String n){int p=n.lastIndexOf('.');return p<=0||p==n.length()-1?"(sem extensão)":n.substring(p).toLowerCase(Locale.ROOT);}
  private static String cat(Node n){String e=extension(n.name);
    if(Arrays.asList(".png",".jpg",".jpeg",".webp",".gif",".bmp",".svg").contains(e))return "Imagens";
    if(Arrays.asList(".mp4",".mkv",".mov",".webm",".avi").contains(e))return "Vídeos";
    if(Arrays.asList(".mp3",".wav",".ogg",".flac",".aac").contains(e))return "Áudios";
    if(Arrays.asList(".zip",".rar",".7z",".tar",".gz").contains(e))return "Compactados";
    if(Arrays.asList(".pdf",".doc",".docx",".txt",".rtf",".odt").contains(e))return "Documentos";
    if(Arrays.asList(".html",".css",".js",".json",".yaml",".yml",".java",".kt",".xml").contains(e))return "Código";
    return "Outros";
  }
  private static String line(Node n){return (n.dir?"[DIR] ":"[ARQ] ")+n.path+" · "+MainActivity.sizeString(n.size)+" · "+MainActivity.dateString(n.modified)+"\n";}
  private static String timestamp(long value,String format){return new SimpleDateFormat(format,Locale.ROOT).format(new Date(value));}
  private static String top(List<Node> list,int n,Comparator<Node> order){
    ArrayList<Node> copy=new ArrayList<>(list);Collections.sort(copy,order);
    StringBuilder b=new StringBuilder();int limit=Math.min(n,copy.size());
    for(int i=0;i<limit;i++)b.append(i+1).append(". ").append(line(copy.get(i)));
    return b.length()==0?"Nenhuma entrada disponível.\n":b.toString();
  }
  private static String grouped(List<Node> nodes,Grouping group){
    TreeMap<String,long[]> result=new TreeMap<>();for(Node n:nodes)if(!n.dir){
      String key=group.apply(n);long[] arr=result.get(key);
      if(arr==null){arr=new long[2];result.put(key,arr);}arr[0]++;if(n.size>0)arr[1]+=n.size;
    }
    StringBuilder b=new StringBuilder();for(Map.Entry<String,long[]> entry:result.entrySet()){
      b.append(entry.getKey()).append(": ").append(entry.getValue()[0]).append(" item(ns), ")
        .append(MainActivity.sizeString(entry.getValue()[1])).append(" conhecidos\n");
    }return b.toString();
  }
  private interface Grouping{String apply(Node n);}
  private interface Rule{boolean matches(Node n);}
  private static String filtered(Scan s,Rule rule,int limit){
    StringBuilder b=new StringBuilder();int found=0;
    for(Node n:s.nodes)if(rule.matches(n)){found++;if(found<=limit)b.append(line(n));}
    return "Encontrados: "+found+"\n"+b.toString()+(found>limit?"\n[Exibidos os primeiros "+limit+" resultados]":"");
  }
  private static String summary(Scan s){return "Pastas: "+s.dirs+"\nArquivos: "+s.files+
    "\nTamanho conhecido somado: "+MainActivity.sizeString(s.knownBytes)+
    "\nArquivos sem tamanho informado: "+s.unknownSize+
    "\nArquivos sem modificação conhecida: "+s.unknownDate+
    "\nEntradas varridas: "+s.nodes.size()+"\nTempo: "+(System.currentTimeMillis()-s.started)+" ms";
  }
  private void auditMenu(){
    if(a.inZip||a.root==null||a.docId==null){a.fail("Abra uma pasta normal (com acesso autorizado pelo Android).");return;}
    final String[] opts={
      "01 · Resumo recursivo: arquivos, pastas e tamanho",
      "02 · 30 maiores arquivos",
      "03 · 30 arquivos mais recentes",
      "04 · 30 arquivos mais antigos",
      "05 · Encontrar arquivos vazios",
      "06 · Encontrar pastas vazias",
      "07 · Listar arquivos e pastas ocultos",
      "08 · Arquivos sem extensão",
      "09 · Distribuição por extensão",
      "10 · Distribuição por categoria",
      "11 · Distribuição por ano de modificação",
      "12 · Distribuição por mês de modificação",
      "13 · Distribuição por MIME",
      "14 · Arquivos com tamanho desconhecido",
      "15 · Arquivos com data desconhecida",
      "16 · Arquivos maiores que N MB",
      "17 · Arquivos não modificados há N dias",
      "18 · Duplicados recursivos (SHA-256, limites)",
      "19 · Inventário de caminhos relativos",
      "20 · Nomes iguais em pastas diferentes",
      "21 · 30 caminhos mais profundos",
      "22 · Diretórios com mais itens imediatos",
      "23 · Pastas ordenadas por tamanho acumulado (estimado)"
    };
    new AlertDialog.Builder(a).setTitle("Auditorias recursivas · 23 opções")
      .setItems(opts,(d,w)->{
        if(w==15){a.prompt("Limite mínimo de tamanho (MB)","100",v->{try{
          long mb=Long.parseLong(v);if(mb<0||mb>1000000)throw new NumberFormatException();
          beginAudit(15,mb*1024L*1024L);
        }catch(Exception e){a.fail("Tamanho inválido");}});}
        else if(w==16){a.prompt("Mais antigos que quantos dias?","365",v->{try{
          long days=Long.parseLong(v);if(days<0||days>36500)throw new NumberFormatException();
          beginAudit(16,days);
        }catch(Exception e){a.fail("Número de dias inválido");}});}
        else beginAudit(w,0);
      }).setNegativeButton("Voltar",(d,w)->showMenu()).show();
  }
  private void beginAudit(int mode,long value){
    if(a.root==null||a.docId==null||a.inZip){a.fail("Escolha uma pasta para auditoria");return;}
    final Uri tree=a.root;final String start=a.docId;
    work("Auditoria recursiva · opção "+(mode+1),()->{
      Scan s=scan(tree,start);checkCancel();String result;
      List<Node> files=new ArrayList<>();for(Node n:s.nodes)if(!n.dir)files.add(n);
      switch(mode){
        case 0:result=summary(s);break;
        case 1:{List<Node> known=new ArrayList<>();for(Node n:files)if(n.size>=0)known.add(n);
          result=top(known,30,(x,y)->Long.compare(y.size,x.size));break;}
        case 2:{List<Node> known=new ArrayList<>();for(Node n:files)if(n.modified>0)known.add(n);
          result=top(known,30,(x,y)->Long.compare(y.modified,x.modified));break;}
        case 3:{List<Node> known=new ArrayList<>();for(Node n:files)if(n.modified>0)known.add(n);
          result=top(known,30,(x,y)->Long.compare(x.modified,y.modified));break;}
        case 4:result=filtered(s,n->!n.dir&&n.size==0,100);break;
        case 5:result=filtered(s,n->n.dir&&s.children.containsKey(n.id)&&s.children.get(n.id)==0,100);break;
        case 6:result=filtered(s,n->n.name.startsWith("."),120);break;
        case 7:result=filtered(s,n->!n.dir&&extension(n.name).equals("(sem extensão)"),120);break;
        case 8:result=grouped(files,n->extension(n.name));break;
        case 9:result=grouped(files,n->cat(n));break;
        case 10:result=grouped(files,n->n.modified>0?timestamp(n.modified,"yyyy"):"(sem data)");break;
        case 11:result=grouped(files,n->n.modified>0?timestamp(n.modified,"yyyy-MM"):"(sem data)");break;
        case 12:result=grouped(files,n->n.mime==null?"(sem MIME)":n.mime);break;
        case 13:result=filtered(s,n->!n.dir&&n.size<0,120);break;
        case 14:result=filtered(s,n->!n.dir&&n.modified<=0,120);break;
        case 15:result=filtered(s,n->!n.dir&&n.size>=value,120);break;
        case 16:{long cutoff=System.currentTimeMillis()-value*86400000L;
          result=filtered(s,n->!n.dir&&n.modified>0&&n.modified<cutoff,120);break;}
        case 17:result=duplicates(s,tree);break;
        case 18:{StringBuilder b=new StringBuilder();for(Node n:s.nodes){if(b.length()>27000)break;b.append(n.path).append('\n');}
          result="Itens varridos: "+s.nodes.size()+"\n\n"+b+(b.length()>27000?"\n[Inventário truncado para exibição]":"");break;}
        case 19:{Map<String,List<String>> names=new TreeMap<>();for(Node n:files){String k=n.name.toLowerCase(Locale.ROOT);
          names.computeIfAbsent(k,z->new ArrayList<>()).add(n.path);}
          StringBuilder b=new StringBuilder();int count=0;for(Map.Entry<String,List<String>> e:names.entrySet())if(e.getValue().size()>1){
            count++;if(b.length()<26000){b.append(e.getKey()).append(":\n");for(String p:e.getValue())b.append("  ").append(p).append('\n');}}
          result="Nomes repetidos: "+count+" grupo(s)\n\n"+b;break;}
        case 20:result=top(s.nodes,30,(x,y)->Integer.compare(y.depth,x.depth));break;
        case 21:{List<Map.Entry<String,Integer>> counts=new ArrayList<>(s.children.entrySet());
          Collections.sort(counts,(x,y)->Integer.compare(y.getValue(),x.getValue()));
          Map<String,String> paths=new HashMap<>();for(Node n:s.nodes)if(n.dir)paths.put(n.id,n.path);
          StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(30,counts.size());i++){
            Map.Entry<String,Integer> e=counts.get(i);
            b.append(e.getValue()).append(" filhos · ").append(paths.containsKey(e.getKey())?paths.get(e.getKey()):"(pasta inicial)").append('\n');}
          result=b.toString();break;}
        case 22:{Map<String,Long> sums=new HashMap<>();for(Node n:files)if(n.size>=0){
          String p=n.path;while(p.contains("/")){p=p.substring(0,p.lastIndexOf('/'));
            sums.put(p,sums.getOrDefault(p,0L)+n.size);}}
          List<Map.Entry<String,Long>> all=new ArrayList<>(sums.entrySet());
          Collections.sort(all,(x,y)->Long.compare(y.getValue(),x.getValue()));
          StringBuilder b=new StringBuilder("Somente tamanho conhecido; soma inclui subpastas.\n\n");
          for(int i=0;i<Math.min(30,all.size());i++)b.append(MainActivity.sizeString(all.get(i).getValue())).append(" · ").append(all.get(i).getKey()).append('\n');
          result=b.toString();break;}
        default:result="Opção desconhecida";
      }
      return "Pasta autorizada: "+start+"\n"+summary(s)+"\n\n--- RESULTADO ---\n"+result+
        "\n\nAuditoria apenas de leitura. Não garante acesso a pastas restritas pelo Android.";
    });
  }
  private String duplicates(Scan s,Uri tree)throws Exception{
    Map<Long,List<Node>> sizes=new HashMap<>();
    for(Node n:s.nodes)if(!n.dir&&n.size>=0&&n.size<=16L*1024*1024)
      sizes.computeIfAbsent(n.size,k->new ArrayList<>()).add(n);
    int candidates=0;for(List<Node> g:sizes.values())if(g.size()>1)candidates+=g.size();
    if(candidates>60)return "Há "+candidates+" candidatos do mesmo tamanho. Limite de 60 excedido; refine a pasta e repita. Nenhuma conclusão sobre duplicados foi feita.";
    Map<String,List<String>> sha=new LinkedHashMap<>();int done=0;
    for(List<Node> group:sizes.values())if(group.size()>1)for(Node n:group){checkCancel();
      String dig=checksumHex(fileUri(tree,n.id),"SHA-256");
      sha.computeIfAbsent(dig,k->new ArrayList<>()).add(n.path);progress("Checando duplicados",++done);
    }
    int groups=0;StringBuilder b=new StringBuilder();
    for(List<String> list:sha.values())if(list.size()>1){groups++;b.append("GRUPO ").append(groups).append("\n");
      for(String path:list)b.append("  ").append(path).append('\n');b.append('\n');}
    return "Candidatos verificados: "+done+"\nGrupos duplicados encontrados: "+groups+
      "\nSomente arquivos de até 16 MB (máximo 60 candidatos). Não verifica arquivos maiores.\n\n"+b;
  }

  private void recordsMenu(){
    final String[] options={
      "01 · Histórico completo das operações",
      "02 · Histórico: entradas com ERRO / FALHA",
      "03 · Histórico: entradas concluídas",
      "04 · Estatísticas resumidas do histórico",
      "05 · Exportar histórico para arquivo TXT",
      "06 · Exportar último relatório para TXT",
      "07 · Copiar último relatório",
      "08 · Limpar histórico (com confirmação)",
      "09 · Autorizações de pasta concedidas pelo Android",
      "10 · Informações da pasta ativa",
      "11 · Espaço do volume onde o app armazena dados"
    };
    new AlertDialog.Builder(a).setTitle("Histórico, exportação e diagnóstico")
      .setItems(options,(d,w)->{
        switch(w){
          case 0:report("Histórico completo",allHistory());break;
          case 1:report("Histórico com falhas",filteredHistory(true));break;
          case 2:report("Histórico concluído",filteredHistory(false));break;
          case 3:historyStats();break;
          case 4:exportText("historico_exploradorzip.txt", "Histórico das operações",allHistory());break;
          case 5:if(lastText.isEmpty()){a.fail("Abra um relatório nesta sessão antes de exportar.");return;}
            exportText("auditoria_exploradorzip.txt",lastTitle,lastText);break;
          case 6:if(lastText.isEmpty()){a.fail("Nenhum relatório recente.");return;}
            a.copyText("último relatório",lastText);break;
          case 7:confirmClear();break;
          case 8:permissions();break;
          case 9:activeFolder();break;
          case 10:storageVolume();break;
        }
      }).setNegativeButton("Voltar",(d,w)->showMenu()).show();
  }
  private String allHistory(){
    StringBuilder b=new StringBuilder("Histórico salvo pelo aplicativo. Máximo: 150 entradas.\n\n");
    for(String item:a.operationLog)b.append(item).append('\n');
    if(a.operationLog.isEmpty())b.append("Nenhuma operação registrada.\n");
    return b.toString();
  }
  private static boolean isProblem(String s){String t=s.toLowerCase(Locale.ROOT);
    return t.contains("erro")||t.contains("falha")||t.contains("não suportada");}
  private String filteredHistory(boolean problems){StringBuilder b=new StringBuilder();int count=0;
    for(String log:a.operationLog)if(isProblem(log)==problems){count++;b.append(log).append('\n');}
    return "Entradas: "+count+"\n\n"+(count==0?"Nenhuma.":b.toString());
  }
  private void historyStats(){int failures=0,ok=0;
    for(String entry:a.operationLog)if(isProblem(entry))failures++;else ok++;
    report("Estatísticas do histórico","Entradas registradas: "+a.operationLog.size()+"\nCom indicação de falha: "+failures+
      "\nSem indicação de falha: "+ok+"\n\nAviso: baseia-se apenas no texto das operações salvas; não é uma auditoria completa de todos os movimentos anteriores.");
  }
  private void confirmClear(){new AlertDialog.Builder(a).setTitle("Limpar histórico?")
    .setMessage("Apaga apenas os registros salvos pelo aplicativo. Não modifica arquivos.")
    .setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,w)->{
      a.operationLog.clear();a.prefs.edit().remove("oplog").apply();a.status.setText("Histórico apagado.");
    }).show();}
  private void permissions(){StringBuilder b=new StringBuilder("Permissões persistentes de documentos:\n\n");
    for(android.content.UriPermission p:a.getContentResolver().getPersistedUriPermissions()){
      b.append(p.getUri()).append("\nLeitura: ").append(p.isReadPermission()).append(" / Escrita: ")
        .append(p.isWritePermission()).append("\n\n");}
    b.append("\nEssas permissões não liberam pastas privadas de outros aplicativos.");
    report("Permissões SAF",b.toString());
  }
  private void activeFolder(){report("Pasta ativa",
    "Origem autorizada: "+a.root+"\nIdentificador atual: "+a.docId+
    "\nCaminho exibido: "+String.join("/",a.pathNames)+
    "\nModo ZIP: "+a.inZip+"\nArquivos ocultos visíveis: "+a.hidden+
    "\nItens listados após filtros: "+a.rows.size()+
    "\nItens marcados: "+a.selected.size()+
    "\n\nCaminhos exibidos são relativos à pasta autorizada; não necessariamente caminhos físicos.");}
  private void storageVolume(){
    try{java.io.File base=a.getExternalFilesDir(null);if(base==null)throw new IOException("Volume indisponível");
      StatFs fs=new StatFs(base.getAbsolutePath());
      long total=fs.getTotalBytes(),available=fs.getAvailableBytes();
      report("Armazenamento acessível ao aplicativo",
        "Volume da área externa do aplicativo (não representa obrigatoriamente a pasta SAF atual).\n"+
        "Capacidade: "+MainActivity.sizeString(total)+"\nDisponível: "+MainActivity.sizeString(available)+
        "\nUtilizado: "+MainActivity.sizeString(Math.max(0,total-available)));
    }catch(Exception e){a.fail("Não foi possível ler espaço: "+e.getMessage());}
  }
  private void exportText(String suggestedName,String title,String text){
    lastTitle=title;lastText=text;
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,suggestedName);
    try{a.startActivityForResult(i,MainActivity.EXPORT_REPORT);}catch(Exception e){a.fail("Não foi possível abrir seletor de destino: "+e.getMessage());}
  }
  void writeLastReport(Uri uri){
    if(lastText==null||lastText.isEmpty()){a.fail("Nenhum relatório para salvar.");return;}
    try(OutputStream out=a.getContentResolver().openOutputStream(uri,"w")){
      if(out==null)throw new IOException("Destino indisponível");
      out.write((lastTitle+"\n\n"+lastText).getBytes("UTF-8"));out.flush();
      Toast.makeText(a,"Relatório TXT salvo",Toast.LENGTH_LONG).show();
    }catch(Exception e){a.fail("Erro ao exportar relatório: "+e.getMessage());}
  }
}
