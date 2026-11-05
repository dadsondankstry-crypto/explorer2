package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Copia somente arquivos ausentes, sem sobrescrever, apagar ou mover originais. */
final class SyncTools {
  private static final int LIMIT=2500, DEPTH=24;
  private final MainActivity a;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private Uri source,target;
  private boolean hidden=true, running=false;
  private volatile boolean cancel=false;
  private volatile int visited=0,completed=0;
  private volatile String stage="Parado";
  private String report="Nenhuma analise.",srcLabel="",dstLabel="";
  private Plan lastPlan=null;
  static final class Entry {
    String path,docId,mime; boolean dir; long size;
    Entry(String p,String id,String m,boolean d,long sz){path=p;docId=id;mime=m;dir=d;size=sz;}
  }
  static final class Plan {
    final Uri src,dst;final LinkedHashMap<String,Entry> originals;
    final LinkedHashSet<String> targetPaths;
    final ArrayList<Entry> missingDirs=new ArrayList<>(),missingFiles=new ArrayList<>();
    int filesSkipped=0,conflicts=0;long knownBytes=0;
    Plan(Uri a,Uri b,LinkedHashMap<String,Entry> s,LinkedHashSet<String> t){src=a;dst=b;originals=s;targetPaths=t;}
  }
  SyncTools(MainActivity a){this.a=a;}
  void menu(){
    String[] opts={"Selecionar pasta ORIGEM (leitura)","Selecionar pasta DESTINO (gravacao)",
      "Incluir nomes ocultos: "+(hidden?"SIM":"NAO"),
      "1. ANALISAR e criar pre-visualizacao (nao altera arquivos)",
      "2. COPIAR SOMENTE AUSENTES (exige previa)",
      "Progresso / cancelar", "Ultimo relatorio", "Copiar relatorio", "Limpar previa", "Limitacoes e seguranca"};
    new AlertDialog.Builder(a).setTitle("Sincronizacao segura 3.4 — unilateral")
      .setItems(opts,(d,n)->{switch(n){
        case 0:pick(MainActivity.SYNC_SOURCE);break;
        case 1:pick(MainActivity.SYNC_TARGET);break;
        case 2:hidden=!hidden;lastPlan=null;toast("Ocultos "+(hidden?"incluidos":"ignorados"));break;
        case 3:preview();break;
        case 4:confirmApply();break;
        case 5:progress();break;
        case 6:show("Relatorio 3.4",report);break;
        case 7:android.content.ClipboardManager c=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText("Sync 3.4",report));toast("Relatorio copiado");break;
        case 8:if(!running){lastPlan=null;report="Previa limpa.";toast("Previa limpa");}break;
        case 9:show("Limitacoes importantes","Copia APENAS arquivos ausentes da origem ao destino. NUNCA sobrescreve arquivos existentes, NAO exclui arquivos, NAO move, NAO faz sincronizacao bidirecional e NAO compara conteudos para atualizar arquivos. Destino nao pode ser a mesma arvore nem subpasta da origem quando identificavel. Ate 2500 itens e 24 niveis por arvore. Precisa de permissoes SAF; alguns provedores recusam gravacao. Um cancelamento nao desfaz arquivos ja copiados. O arquivo parcialmente gravado pela operacao atual e removido quando possivel. Evite selecionar armazenamento de rede instavel e teste com dados descartaveis.");break;
      }}).setNegativeButton("Fechar",null).show();
  }
  private void pick(int code){
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    a.startActivityForResult(i,code);
  }
  void selected(int code,Uri uri,Intent data){
    if(running){toast("Conclua a operacao atual");return;}
    int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try{a.getContentResolver().takePersistableUriPermission(uri,flags);}catch(Exception ignored){}
    if(code==MainActivity.SYNC_SOURCE){source=uri;srcLabel=uri.getLastPathSegment();}
    else{target=uri;dstLabel=uri.getLastPathSegment();}
    lastPlan=null;
    toast("Pasta "+(code==MainActivity.SYNC_SOURCE?"origem":"destino")+" selecionada");
  }
  private void safeRoots()throws IOException{
    if(source==null||target==null)throw new IOException("Selecione origem e destino");
    if(source.equals(target))throw new IOException("Origem e destino iguais");
    String s=DocumentsContract.getTreeDocumentId(source),t=DocumentsContract.getTreeDocumentId(target);
    if(s.equals(t)||t.startsWith(s+"/"))throw new IOException("Destino dentro da origem: operacao bloqueada");
    // Distintas URIs de provedores podem apontar para as mesmas pastas. Evitamos nomes duplicados no destino.
  }
  private void preview(){
    if(running){toast("Uma operacao ja esta em andamento");return;}
    try{safeRoots();}catch(Exception e){toast(e.getMessage());return;}
    final Uri s=source,t=target; final boolean h=hidden;
    running=true;cancel=false;visited=0;completed=0;stage="Analisando origem";lastPlan=null;
    worker.execute(()->{
      try{
        LinkedHashMap<String,Entry> src=scan(s,h);
        stage="Analisando destino";
        LinkedHashMap<String,Entry> dest=scan(t,true); // hidden no destino sempre: nao sobrescrever ocultos invisiveis!
        Plan p=new Plan(s,t,src,new LinkedHashSet<>(dest.keySet()));
        for(Entry item:src.values()){
          Entry other=dest.get(item.path);
          if(other!=null){if(item.dir!=other.dir)p.conflicts++;else if(!item.dir)p.filesSkipped++;continue;}
          if(item.dir)p.missingDirs.add(item);
          else{p.missingFiles.add(item);if(item.size>0)p.knownBytes+=item.size;}
        }
        p.missingDirs.sort(Comparator.comparingInt(x->depth(x.path)));
        lastPlan=p;
        StringBuilder b=new StringBuilder("PREVIA 3.4 — NAO ALTEROU ARQUIVOS\n\nOrigem: "+srcLabel+"\nDestino: "+dstLabel+
          "\nPastas novas: "+p.missingDirs.size()+"\nArquivos novos: "+p.missingFiles.size()+
          "\nArquivos existentes ignorados: "+p.filesSkipped+"\nConflitos de tipo: "+p.conflicts+
          "\nBytes conhecidos a copiar: "+p.knownBytes+"\n\nItens ausentes (ate 100):\n");
        int k=0;for(Entry e:p.missingDirs){if(k++>=100)break;b.append("+ [PASTA] ").append(e.path).append('\n');}
        for(Entry e:p.missingFiles){if(k++>=100)break;b.append("+ ").append(e.path).append('\n');}
        b.append("\nNenhum existente sera substituido nem excluido. Refaça a previa se as pastas forem alteradas.");
        finish(b.toString());
      }catch(Exception ex){lastPlan=null;finish("Previa nao concluida: "+ex.getMessage());}
    });
    toast("Analise iniciada. Consulte Progresso.");
  }
  private static int depth(String path){return path.isEmpty()?0:path.split("/",-1).length;}
  private LinkedHashMap<String,Entry> scan(Uri tree,boolean includeHidden)throws IOException{
    LinkedHashMap<String,Entry> map=new LinkedHashMap<>();
    ArrayDeque<Entry> dirs=new ArrayDeque<>();
    dirs.add(new Entry("",DocumentsContract.getTreeDocumentId(tree),DocumentsContract.Document.MIME_TYPE_DIR,true,0));
    while(!dirs.isEmpty()){
      checkCancel();Entry dir=dirs.removeFirst();
      if(depth(dir.path)>DEPTH)throw new IOException("Limite de profundidade de "+DEPTH);
      Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,dir.docId);
      try(Cursor c=a.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
        if(c==null)throw new IOException("Nao foi possivel listar "+dir.path);
        while(c.moveToNext()){
          checkCancel();if(++visited>LIMIT*2)throw new IOException("Limite de itens atingido: "+LIMIT+" por arvore");
          String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
          if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\"))continue;
          if(!includeHidden&&name.startsWith("."))continue;
          String path=dir.path.isEmpty()?name:dir.path+"/"+name;
          if(depth(path)>DEPTH)throw new IOException("Profundidade excedida");
          boolean isDir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
          Entry item=new Entry(path,id,mime,isDir,c.isNull(3)?-1:c.getLong(3));
          if(map.put(path,item)!=null)throw new IOException("Caminho duplicado: "+path);
          if(map.size()>LIMIT)throw new IOException("Limite de "+LIMIT+" itens por arvore");
          if(isDir)dirs.addLast(item);
        }
      }catch(SecurityException ex){throw new IOException("Acesso negado: "+dir.path,ex);}
    }
    return map;
  }
  private void confirmApply(){
    if(running){toast("Uma operacao esta em andamento");return;}
    final Plan p=lastPlan;
    if(p==null){toast("Execute ANALISAR primeiro");return;}
    if(!p.src.equals(source)||!p.dst.equals(target)){toast("Refaca a previa");return;}
    if(p.conflicts>0){toast("Existem conflitos entre arquivo e pasta. Resolva antes de copiar.");return;}
    if(p.missingFiles.isEmpty()&&p.missingDirs.isEmpty()){toast("Nenhum item novo para copiar");return;}
    new AlertDialog.Builder(a).setTitle("Confirmar copia unilateral?")
      .setMessage("Criar "+p.missingDirs.size()+" pastas e copiar ate "+p.missingFiles.size()+" arquivos AUSENTES. Sem sobrescrever ou excluir. A previa pode estar desatualizada: conflitos detectados na hora serao ignorados. Continuar?")
      .setNegativeButton("Cancelar",null).setPositiveButton("Copiar ausentes",(d,n)->apply(p)).show();
  }
  private void apply(Plan p){
    try{safeRoots();}catch(Exception e){toast(e.getMessage());return;}
    if(running)return;
    running=true;cancel=false;completed=0;stage="Preparando copia";lastPlan=null;
    worker.execute(()->{
      int created=0,skipped=0,failed=0,dirs=0;ArrayList<String> errors=new ArrayList<>();
      try{
        // Construir indice NOVO do destino. Nunca pressupor que a previa permaneceu valida.
        visited=0;LinkedHashMap<String,Entry> existing=scan(p.dst,true);
        HashMap<String,String> parentIds=new HashMap<>();
        parentIds.put("",DocumentsContract.getTreeDocumentId(p.dst));
        for(Entry e:existing.values())if(e.dir)parentIds.put(e.path,e.docId);
        ArrayList<Entry> sortedDirs=new ArrayList<>(p.missingDirs);
        sortedDirs.sort(Comparator.comparingInt(x->depth(x.path)));
        for(Entry e:sortedDirs){
          checkCancel();stage="Criando pasta: "+e.path;
          Entry there=existing.get(e.path);if(there!=null){if(there.dir)parentIds.put(e.path,there.docId);else{failed++;errors.add("Conflito de tipo: "+e.path);}continue;}
          String par=parentOf(e.path),parentId=parentIds.get(par);
          if(parentId==null){failed++;errors.add("Pasta pai indisponivel: "+e.path);continue;}
          try{
            Uri createdUri=DocumentsContract.createDocument(a.getContentResolver(),docUri(p.dst,parentId),DocumentsContract.Document.MIME_TYPE_DIR,baseName(e.path));
            if(createdUri==null)throw new IOException("createDocument retornou vazio");
            String actual=displayName(createdUri);
            if(!baseName(e.path).equals(actual)){try{DocumentsContract.deleteDocument(a.getContentResolver(),createdUri);}catch(Exception ignored){}throw new IOException("Nome alterado pelo provedor: "+actual);}
            String id=DocumentsContract.getDocumentId(createdUri);
            parentIds.put(e.path,id);existing.put(e.path,new Entry(e.path,id,DocumentsContract.Document.MIME_TYPE_DIR,true,0));dirs++;
          }catch(Exception ex){failed++;errors.add("Pasta "+e.path+": "+ex.getMessage());}
        }
        for(Entry e:p.missingFiles){
          checkCancel();stage="Copiando: "+e.path;
          // Evitar recriar se existe, inclusive nomes ocultos que nao foram selecionados.
          if(existing.containsKey(e.path)){skipped++;continue;}
          String parentId=parentIds.get(parentOf(e.path));
          if(parentId==null){failed++;errors.add("Sem pasta pai: "+e.path);continue;}
          Uri output=null;
          try{
            String mime=e.mime==null||e.mime.isEmpty()?"application/octet-stream":e.mime;
            output=DocumentsContract.createDocument(a.getContentResolver(),docUri(p.dst,parentId),mime,baseName(e.path));
            if(output==null)throw new IOException("Nao foi possivel criar arquivo");
            if(!baseName(e.path).equals(displayName(output)))throw new IOException("Provedor renomeou arquivo; transferencia cancelada");
            Uri original=docUri(p.src,e.docId);
            try(InputStream in=a.getContentResolver().openInputStream(original);
                OutputStream out=a.getContentResolver().openOutputStream(output,"w")){
              if(in==null||out==null)throw new IOException("Falha ao abrir arquivo");
              byte[] buf=new byte[32768];int n;
              while((n=in.read(buf))!=-1){checkCancel();out.write(buf,0,n);}
              out.flush();
            }
            String id=DocumentsContract.getDocumentId(output);
            existing.put(e.path,new Entry(e.path,id,mime,false,e.size));created++;
          }catch(Exception ex){
            failed++;errors.add("Arquivo "+e.path+": "+ex.getMessage());
            if(output!=null)try{DocumentsContract.deleteDocument(a.getContentResolver(),output);}catch(Exception ignored){errors.add("ATENCAO: arquivo parcial pode permanecer: "+e.path);}
            if(cancel)break;
          }
          completed++;
        }
      }catch(Exception ex){errors.add("Operacao interrompida: "+ex.getMessage());}
      StringBuilder b=new StringBuilder("COPIA UNILATERAL 3.4 — RESULTADO\n\nPastas criadas: "+dirs+
        "\nArquivos copiados: "+created+"\nExistentes ignorados: "+skipped+
        "\nFalhas: "+failed+"\nCancelado: "+(cancel?"SIM":"NAO")+
        "\n\nOriginais preservados. Nenhum arquivo preexistente foi intencionalmente sobrescrito ou excluido.\n\n");
      for(int i=0;i<Math.min(100,errors.size());i++)b.append(errors.get(i)).append('\n');
      finish(b.toString());
    });
    toast("Copia iniciada. Mantenha o aplicativo aberto.");
  }
  private static String parentOf(String p){int k=p.lastIndexOf('/');return k<0?"":p.substring(0,k);}
  private static String baseName(String p){int k=p.lastIndexOf('/');return p.substring(k+1);}
  private Uri docUri(Uri tree,String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
  private String displayName(Uri uri)throws IOException{
    try(Cursor c=a.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst())return c.getString(0);
    }catch(Exception ex){throw new IOException("Nome nao verificavel",ex);}
    throw new IOException("Nome nao verificavel");
  }
  private void checkCancel()throws IOException{if(cancel)throw new IOException("Cancelado pelo usuario");}
  private void progress(){new AlertDialog.Builder(a).setTitle("Sincronizacao 3.4")
      .setMessage((running?"Em andamento":"Parado")+"\nEtapa: "+stage+"\nItens examinados: "+visited+"\nArquivos processados: "+completed)
      .setPositiveButton("Fechar",null).setNegativeButton("Cancelar",(d,w)->{cancel=true;toast("Cancelamento solicitado");}).show();}
  private void finish(String r){a.runOnUiThread(()->{running=false;stage="Concluido";report=r;show("Resultado 3.4",r);});}
  private void show(String title,String s){new AlertDialog.Builder(a).setTitle(title).setMessage(s).setPositiveButton("Fechar",null).show();}
  private void toast(String s){Toast.makeText(a,s,Toast.LENGTH_LONG).show();}
}
