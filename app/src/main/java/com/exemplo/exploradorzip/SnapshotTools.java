package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Ferramentas offline de auditoria de backups. NUNCA escreve nos arquivos analisados. */
final class SnapshotTools {
  static final int MAX_ENTRIES=15000;
  static final long MAX_UNPACKED=2L*1024*1024*1024;
  static final int MAX_MANIFEST=2*1024*1024;
  private final MainActivity activity;
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private volatile boolean busy=false,cancel=false;
  private volatile int scanned=0;
  private volatile long scannedBytes=0;
  private volatile String active="",report="Ainda não há relatório.";
  private Uri first,second;
  private String firstName="",secondName="";
  private boolean verifyManifest=true;

  static final class Item {
    String path,hash;long size;
    Item(String path,String hash,long size){this.path=path;this.hash=hash;this.size=size;}
  }
  static final class Snapshot {
    TreeMap<String,Item> items=new TreeMap<>();
    ArrayList<String> problems=new ArrayList<>();
    long bytes;int folders;
    int checkedManifest, missingManifest, incorrectManifest;
  }
  SnapshotTools(MainActivity a){activity=a;}
  void menu(){
    String[] actions={
      "Selecionar backup A (antigo)","Selecionar backup B (novo)",
      "Comparar A com B · nomes, tamanho e SHA-256",
      "Auditar backup A · conteúdo e manifesto",
      "Auditar backup B · conteúdo e manifesto",
      "Verificar manifesto SHA-256: "+(verifyManifest?"SIM":"NÃO"),
      "Progresso / solicitar cancelamento", "Ver relatório completo", "Copiar relatório",
      "Apagar seleções A/B (não apaga arquivos)", "Limites e privacidade"};
    new AlertDialog.Builder(activity).setTitle("Snapshots 3.1 · comparar backups").setItems(actions,(dialog,index)->{
      switch(index){
        case 0: pick(MainActivity.SNAPSHOT_A);break;
        case 1: pick(MainActivity.SNAPSHOT_B);break;
        case 2: compare();break;
        case 3: audit(first,firstName);break;
        case 4: audit(second,secondName);break;
        case 5: verifyManifest=!verifyManifest;notice("Verificar manifesto: "+(verifyManifest?"ligado":"desligado"));break;
        case 6: state();break;
        case 7: popup("Último relatório",report);break;
        case 8: copy();break;
        case 9: first=null;second=null;firstName="";secondName="";notice("Seleções apagadas; seus arquivos não foram modificados");break;
        case 10: popup("Segurança e limites","A auditoria é somente leitura. Não copia, exclui, sobrescreve ou restaura documentos. Até 15 mil entradas e 2 GiB de conteúdo descompactado por ZIP. Para reduzir riscos de ZIP bomb, a leitura interrompe ao atingir limites. Leitura de SHA-256 pode demorar. O relatório fica somente em memória até ser copiado. Funciona sem rede. A verificação de hash não comprova a autoria do arquivo.");break;
      }
    }).setNegativeButton("Fechar",null).show();
  }
  private void pick(int request){
    if(busy){notice("Auditoria em andamento");return;}
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    activity.startActivityForResult(i,request);
  }
  void selected(int request,Uri uri){
    if(uri==null)return;
    if(request==MainActivity.SNAPSHOT_A){first=uri;firstName=activity.queryName(uri);}
    else {second=uri;secondName=activity.queryName(uri);}
    notice((request==MainActivity.SNAPSHOT_A?"Backup A: ":"Backup B: ")+activity.queryName(uri));
  }
  private void compare(){
    if(first==null||second==null){notice("Selecione primeiro os backups A e B");return;}
    if(first.equals(second)){notice("Selecione dois arquivos diferentes");return;}
    if(busy){notice("Outra auditoria em andamento");return;}
    final Uri left=first,right=second;final String leftName=firstName,rightName=secondName;final boolean verify=verifyManifest;
    begin("Comparando snapshots...");
    executor.execute(()->{
      String result;
      try{
        Snapshot old=scan(left,verify); Snapshot newer=scan(right,verify);
        StringBuilder out=new StringBuilder("COMPARAÇÃO DE BACKUPS 3.1 (SOMENTE LEITURA)\n")
          .append("Antigo A: ").append(leftName).append("\nNovo B: ").append(rightName).append("\n")
          .append("A: ").append(old.items.size()).append(" arquivo(s); B: ").append(newer.items.size()).append(" arquivo(s)\n\n");
        int added=0,removed=0,changed=0,kept=0;TreeSet<String> names=new TreeSet<>();names.addAll(old.items.keySet());names.addAll(newer.items.keySet());
        StringBuilder details=new StringBuilder();
        HashMap<String,ArrayList<String>> removedByHash=new HashMap<>();
        HashMap<String,ArrayList<String>> addedByHash=new HashMap<>();
        for(String path:names){
          Item a=old.items.get(path),b=newer.items.get(path);
          if(a==null){added++;details.append("+ NOVO  ").append(path).append("\n");addedByHash.computeIfAbsent(b.hash,k->new ArrayList<>()).add(path);}
          else if(b==null){removed++;details.append("- AUSENTE NO NOVO  ").append(path).append("\n");removedByHash.computeIfAbsent(a.hash,k->new ArrayList<>()).add(path);}
          else if(!a.hash.equals(b.hash)){changed++;details.append("~ ALTERADO  ").append(path).append(" [").append(a.size).append(" → ").append(b.size).append(" bytes]\n");}
          else kept++;
        }
        out.append("Novos: ").append(added).append("\nAusentes no novo: ").append(removed)
           .append("\nConteúdo alterado: ").append(changed).append("\nSem alterações: ").append(kept).append("\n\n")
           .append("DIFERENÇAS\n").append(details.length()==0?"Nenhuma alteração de conteúdo detectada.\n":details.toString());
        int hints=0;
        for(Map.Entry<String,ArrayList<String>> e:removedByHash.entrySet()){
          ArrayList<String> to=addedByHash.get(e.getKey());if(to!=null&&!to.isEmpty()){
            if(hints==0)out.append("\nPOSSÍVEIS RENOMEAÇÕES/MOVIMENTAÇÕES (mesmo hash)\n");
            for(String source:e.getValue()){if(hints++>=40)break;out.append(source).append("  →  ").append(to.get(0)).append("\n");}
          }
          if(hints>=40)break;
        }
        reportManifest(out,"A",old);reportManifest(out,"B",newer);
        result=out.toString();
      }catch(Exception e){result="Comparação não concluída: "+e.getMessage()+"\nNenhum arquivo foi alterado.";}
      finish("Comparação concluída",result);
    });
  }
  private void audit(Uri uri,String filename){
    if(uri==null){notice("Selecione o backup correspondente primeiro");return;}
    if(busy){notice("Outra auditoria em andamento");return;}
    final boolean verify=verifyManifest;
    begin("Auditando "+filename);
    executor.execute(()->{
      String result;
      try{
        Snapshot s=scan(uri,verify);
        StringBuilder out=new StringBuilder("AUDITORIA DE ZIP 3.1\nArquivo: ").append(filename)
          .append("\nArquivos: ").append(s.items.size()).append("\nDiretórios: ").append(s.folders)
          .append("\nBytes descompactados: ").append(s.bytes).append("\n")
          .append("Manif.: ").append(verify?"verificação ativada":"verificação desativada").append("\n");
        reportManifest(out,"ZIP",s);
        out.append("\nExemplos de arquivos (primeiros 80):\n");
        int i=0;for(Item item:s.items.values()){if(i++>=80)break;out.append(item.path).append(" · ").append(item.size).append(" bytes · SHA-256 ").append(item.hash).append("\n");}
        result=out.toString();
      }catch(Exception e){result="Auditoria não concluída: "+e.getMessage()+"\nNenhum arquivo foi alterado.";}
      finish("Auditoria concluída",result);
    });
  }
  private void reportManifest(StringBuilder out,String label,Snapshot s){
    out.append("\nMANIFESTO ").append(label).append(": entradas verificadas=").append(s.checkedManifest)
      .append(", ausentes=").append(s.missingManifest).append(", divergentes=").append(s.incorrectManifest).append("\n");
    for(String issue:s.problems)out.append("! ").append(issue).append("\n");
  }
  private Snapshot scan(Uri uri,boolean verify)throws Exception{
    Snapshot snapshot=new Snapshot();
    TreeMap<String,String> expected=new TreeMap<>();
    ArrayList<String> manifests=new ArrayList<>();
    try(InputStream raw=activity.getContentResolver().openInputStream(uri)){
      if(raw==null)throw new IOException("Arquivo indisponível para leitura");
      try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(raw))){
        ZipEntry entry;byte[] buffer=new byte[32768];int count=0;
        while((entry=zip.getNextEntry())!=null){
          if(cancel)throw new IOException("Cancelado pelo usuário");
          if(++count>MAX_ENTRIES)throw new IOException("ZIP ultrapassou o limite de 15 mil entradas");
          String name=entry.getName();
          if(name==null||name.isEmpty())throw new IOException("Entrada sem nome");
          if(name.startsWith("/")||name.contains("\\")||name.contains("../")||name.equals("..")||name.contains("/./"))throw new IOException("Entrada de caminho inválido no ZIP: "+name);
          active=name;scanned=count;
          if(entry.isDirectory()){snapshot.folders++;zip.closeEntry();continue;}
          MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
          boolean manifest=name.equals("BACKUP_SHA256.txt")||name.endsWith("/BACKUP_SHA256.txt");
          ByteArrayOutputStream manifestData=manifest&&verify?new ByteArrayOutputStream():null;
          int n;
          while((n=zip.read(buffer))!=-1){
            if(cancel)throw new IOException("Cancelado pelo usuário");
            size+=n;snapshot.bytes+=n;scannedBytes+=n;
            if(snapshot.bytes>MAX_UNPACKED)throw new IOException("ZIP ultrapassou 2 GiB descompactados");
            digest.update(buffer,0,n);
            if(manifestData!=null){if(manifestData.size()+n>MAX_MANIFEST)throw new IOException("Manifesto muito grande");manifestData.write(buffer,0,n);}
          }
          zip.closeEntry();
          String hash=hex(digest.digest());
          if(snapshot.items.put(name,new Item(name,hash,size))!=null)throw new IOException("Caminho duplicado no ZIP: "+name);
          if(manifestData!=null)manifests.add(new String(manifestData.toByteArray(),StandardCharsets.UTF_8));
        }
      }
    }
    if(verify&&!manifests.isEmpty()){
      if(manifests.size()>1)snapshot.problems.add("Mais de um manifesto presente; revisar manualmente.");
      for(String text:manifests){
        String[] lines=text.split("\\r?\\n");
        for(String line:lines){
          if(line.trim().isEmpty()||line.startsWith("#"))continue;
          int sep=line.indexOf(" | ");if(sep!=64){snapshot.problems.add("Linha de manifesto inválida");continue;}
          int sep2=line.indexOf(" | ",sep+3);if(sep2<0){snapshot.problems.add("Linha sem caminho no manifesto");continue;}
          String hash=line.substring(0,sep).trim().toLowerCase(Locale.ROOT);
          String name=line.substring(sep2+3);
          if(!hash.matches("[a-f0-9]{64}")){snapshot.problems.add("Hash malformado para "+name);continue;}
          if(expected.put(name,hash)!=null)snapshot.problems.add("Caminho repetido no manifesto: "+name);
        }
      }
      for(Map.Entry<String,String> e:expected.entrySet()){
        Item actual=snapshot.items.get(e.getKey());
        if(actual==null){snapshot.missingManifest++;snapshot.problems.add("Ausente: "+e.getKey());}
        else if(!actual.hash.equals(e.getValue())){snapshot.incorrectManifest++;snapshot.problems.add("Divergente: "+e.getKey());}
        else snapshot.checkedManifest++;
        if(snapshot.problems.size()>100){snapshot.problems.add("Demais problemas omitidos");break;}
      }
      if(expected.isEmpty())snapshot.problems.add("Manifesto presente, mas sem linhas SHA-256 válidas");
    } else if(verify) snapshot.problems.add("Sem BACKUP_SHA256.txt: leitura do ZIP verificada, mas não há manifesto para conferir");
    return snapshot;
  }
  private static String hex(byte[] d){StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  private void begin(String description){busy=true;cancel=false;scanned=0;scannedBytes=0;active=description;}
  private void finish(String title,String result){report=result;activity.runOnUiThread(()->{busy=false;popup(title,result.length()>25000?result.substring(0,25000)+"\n[Relatório truncado na tela; use Copiar relatório]":result);});}
  private void state(){
    new AlertDialog.Builder(activity).setTitle("Progresso 3.1").setMessage("Em andamento: "+busy+"\nEntradas: "+scanned+"\nBytes lidos: "+scannedBytes+"\nAtual: "+active)
      .setPositiveButton("Fechar",null).setNegativeButton(busy?"Cancelar auditoria":"OK",(d,w)->{if(busy){cancel=true;notice("Cancelamento solicitado");}}).show();
  }
  private void popup(String title,String msg){new AlertDialog.Builder(activity).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
  private void notice(String txt){Toast.makeText(activity,txt,Toast.LENGTH_LONG).show();}
  private void copy(){ClipboardManager c=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText("relatorio_backup",report));notice("Relatório copiado");}
}
