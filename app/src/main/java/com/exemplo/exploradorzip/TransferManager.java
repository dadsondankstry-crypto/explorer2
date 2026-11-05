package com.exemplo.exploradorzip;

import android.app.AlertDialog;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.widget.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transferências SAF em lote. Move usa moveDocument, sem fallback de copiar+excluir. */
final class TransferManager {
  private final MainActivity a;
  private final ExecutorService pool=Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelled=new AtomicBoolean(false);
  private volatile boolean running=false;
  private volatile int done=0,failed=0,total=0;
  private volatile String current="", lastReport="Nenhuma transferência executada.";
  private volatile long startTime;
  private boolean stopOnError=false;
  private AlertDialog progressDialog;
  private ProgressBar bar;
  private TextView info;
  private final ArrayList<String> events=new ArrayList<>();
  TransferManager(MainActivity a){this.a=a;}

  void openMenu(){
    String[] options={
      "Executar área de transferência em lote (SAF)",
      "Ver fila preparada",
      "Consultar progresso da operação",
      "Cancelar após o item atual",
      "Último relatório detalhado",
      "Copiar relatório",
      "Limpar relatório",
      "Alternar: parar quando houver erro ("+(stopOnError?"SIM":"NÃO")+")",
      "Verificar se origem e destino estão válidos",
      "Explicação: movimento real, datas e limitações"
    };
    new AlertDialog.Builder(a).setTitle("Transferências 2.9").setItems(options,(d,n)->{
      switch(n){
        case 0: prepare();break;
        case 1: preview();break;
        case 2: progress();break;
        case 3: cancel();break;
        case 4: message("Relatório de transferências",lastReport);break;
        case 5: copyReport();break;
        case 6: if(running){a.fail("Não é possível limpar durante a transferência");}else{lastReport="Nenhuma transferência executada.";events.clear();a.fail("Relatório limpo");}break;
        case 7: stopOnError=!stopOnError;a.fail("Parar no primeiro erro: "+(stopOnError?"ativado":"desativado"));break;
        case 8: diagnose();break;
        case 9: message("Como funciona", "A operação nativa usa DocumentsContract.copyDocument ou moveDocument, conforme Copiar/Recortar. O aplicativo não copia e exclui como alternativa ao MOVE. O provedor pode recusar, alterar datas ou internamente copiar dados. Não há garantia de operação atômica ou de preservar metadados. Para evitar perdas, teste com arquivos sem importância. Ao cancelar, as transferências já concluídas permanecem feitas. O processamento executa enquanto o aplicativo está aberto; não é um serviço persistente em segundo plano.");break;
      }
    }).setNegativeButton("Fechar",null).show();
  }
  private String validation(){
    if(a.clipboardRows.isEmpty()||a.clipboardTree==null||a.clipboardParentId==null)return "Selecione arquivos e use Recortar ou Copiar no menu Arquivos.";
    if(a.inZip||a.root==null||a.docId==null)return "Abra uma pasta de destino comum, não um arquivo ZIP.";
    if(a.clipboardTree.equals(a.root)&&a.clipboardParentId.equals(a.docId))return "Origem e destino são a mesma pasta.";
    return null;
  }
  private void diagnose(){String error=validation();message("Diagnóstico",error==null?"Origem e destino disponíveis. A operação ainda depende das permissões e do suporte do provedor Android.":error);}
  private void preview(){
    String err=validation();if(err!=null){message("Fila",err);return;}
    StringBuilder s=new StringBuilder();s.append("Tipo: ").append(a.clipboardCut?"MOVER":"COPIAR").append("\nItens: ").append(a.clipboardRows.size()).append("\n\n");
    for(int i=0;i<Math.min(a.clipboardRows.size(),80);i++)s.append(i+1).append(". ").append(a.clipboardRows.get(i).name).append("\n");
    if(a.clipboardRows.size()>80)s.append("... e mais ").append(a.clipboardRows.size()-80).append(" itens");
    message("Fila preparada",s.toString());
  }
  private void prepare(){
    if(running){progress();return;}
    String err=validation();if(err!=null){message("Não é possível executar",err);return;}
    final Uri srcTree=a.clipboardTree,dstTree=a.root;
    final String srcId=a.clipboardParentId,dstId=a.docId;
    final boolean move=a.clipboardCut, stop=stopOnError;
    final ArrayList<MainActivity.Row> jobs=new ArrayList<>(a.clipboardRows);
    StringBuilder details=new StringBuilder("Operação: ").append(move?"MOVER (nativo)":"COPIAR (nativo)").append("\n\nQuantidade: ").append(jobs.size());
    details.append("\nConflitos de nome serão tratados como falhas, não como sobrescrita.\nA execução pode ser parcialmente concluída; não há desfazer automático.");
    details.append("\n\nO aplicativo deve continuar aberto durante a operação.");
    new AlertDialog.Builder(a).setTitle("Confirmar transferência 2.9").setMessage(details)
      .setNegativeButton("Cancelar",null).setPositiveButton("Executar",(d,w)->execute(srcTree,dstTree,srcId,dstId,move,stop,jobs)).show();
  }
  private void execute(Uri srcTree,Uri dstTree,String srcId,String dstId,boolean move,boolean stop,ArrayList<MainActivity.Row> jobs){
    if(running)return;
    running=true;cancelled.set(false);done=0;failed=0;total=jobs.size();current="Preparando";startTime=SystemClock.elapsedRealtime();events.clear();
    a.runOnUiThread(this::showProgressDialog);
    pool.execute(()->{
      boolean stoppedForError=false;
      try{
        Uri srcParent=DocumentsContract.buildDocumentUriUsingTree(srcTree,srcId);
        Uri dstParent=DocumentsContract.buildDocumentUriUsingTree(dstTree,dstId);
        for(MainActivity.Row row:jobs){
          if(cancelled.get())break;
          current=row.name;
          a.runOnUiThread(this::updateProgressDialog);
          try{
            if(existsByName(dstTree,dstId,row.name))throw new IOException("Já existe nome igual no destino");
            Uri original=DocumentsContract.buildDocumentUriUsingTree(srcTree,row.id);
            Uri result=move?DocumentsContract.moveDocument(a.getContentResolver(),original,srcParent,dstParent):DocumentsContract.copyDocument(a.getContentResolver(),original,dstParent);
            if(result==null)throw new IOException("Operação recusada pelo provedor");
            done++;
            events.add("OK   "+row.name);
          }catch(Exception e){failed++;events.add("ERRO "+row.name+" — "+safeMessage(e));if(stop){stoppedForError=true;break;}}
          a.runOnUiThread(this::updateProgressDialog);
        }
      }catch(Exception e){failed++;events.add("ERRO geral — "+safeMessage(e));}
      final boolean halted=stoppedForError;
      long secs=(SystemClock.elapsedRealtime()-startTime)/1000;
      StringBuilder report=new StringBuilder();report.append(move?"Movimentação nativa":"Cópia nativa")
        .append("\nRealizados: ").append(done).append("\nFalhas: ").append(failed)
        .append("\nNão processados: ").append(Math.max(0,total-done-failed))
        .append("\nDuração: ").append(secs).append(" s")
        .append("\nSituação: ").append(cancelled.get()?"cancelada":halted?"parada no erro":"finalizada")
        .append("\n\n");
      for(String event:events)report.append(event).append('\n');
      lastReport=report.toString();running=false;
      a.runOnUiThread(()->{
        if(progressDialog!=null){progressDialog.dismiss();progressDialog=null;}
        a.addLog("Transferências 2.9: "+done+" concluídas, "+failed+" falhas");
        // Em movimentações parcialmente concluídas, manter a área de transferência para revisão manual.
        if(move&&done==total){a.clipboardRows.clear();a.clipboardLabel="";}
        a.refresh();message("Resultado da transferência",lastReport);
      });
    });
  }
  private boolean existsByName(Uri tree,String parent,String name)throws Exception{
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
    try(Cursor c=a.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c==null)throw new IOException("Não foi possível conferir os nomes existentes");
      while(c.moveToNext())if(name.equals(c.getString(0)))return true;
      return false;
    }
  }
  private String safeMessage(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
  private void showProgressDialog(){
    if(!running)return;
    LinearLayout body=new LinearLayout(a);body.setPadding(24,14,24,8);body.setOrientation(LinearLayout.VERTICAL);
    bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(Math.max(1,total));body.addView(bar);
    info=new TextView(a);info.setPadding(0,12,0,0);body.addView(info);
    progressDialog=new AlertDialog.Builder(a).setTitle("Transferências em andamento")
      .setView(body).setPositiveButton("Ocultar",(d,w)->{progressDialog=null;})
      .setNegativeButton("Cancelar após o item",(d,w)->{cancelled.set(true);progressDialog=null;})
      .create();progressDialog.show();updateProgressDialog();
  }
  private void updateProgressDialog(){
    if(info!=null&&running&&progressDialog!=null){
      if(bar!=null)bar.setProgress(done+failed);
      info.setText("Concluídos: "+done+" / "+total+"\nFalhas: "+failed+"\nItem atual: "+current+"\nTempo: "+((SystemClock.elapsedRealtime()-startTime)/1000)+" s");
    }
  }
  private void cancel(){if(!running){a.fail("Nenhuma transferência em andamento");return;}cancelled.set(true);a.fail("Cancelamento solicitado; o item atual poderá ser concluído.");}
  private void progress(){if(running){showProgressDialog();return;}message("Situação",lastReport);}
  private void copyReport(){((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Transferências 2.9",lastReport));a.fail("Relatório copiado");}
  private void message(String title,String text){new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK",null).show();}
  void close(){cancelled.set(true);pool.shutdownNow();}
}
