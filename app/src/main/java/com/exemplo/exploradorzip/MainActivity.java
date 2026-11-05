package com.exemplo.exploradorzip;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.TextWatcher;
import android.text.Editable;
import android.text.method.ScrollingMovementMethod;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import android.graphics.drawable.BitmapDrawable;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import java.text.SimpleDateFormat;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
  static final int CONFIG36_EXPORT=55, CONFIG36_IMPORT=56, INVENTORY37_CSV=57;
  static final int FOLDER=1, ZIP=2, EXTRACT=3, MOVE=4, PACK=5, COPY=6, SHARE=7, EXPORT_REPORT=27, BACKUP_DESTINATION=30, BACKUP_VALIDATE=31, SNAPSHOT_A=32, SNAPSHOT_B=33, RESTORE_ZIP=34, RESTORE_FOLDER=35, COMPARE_A=36, COMPARE_B=37, SYNC_SOURCE=38, SYNC_TARGET=39, DELTA_SOURCE=40, DELTA_BASELINE=41, DELTA_OUTPUT=42;
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  final ArrayList<Row> rows=new ArrayList<>();
  final ArrayList<String> pathNames=new ArrayList<>(), pathIds=new ArrayList<>();
  final ArrayList<ZItem> zipItems=new ArrayList<>();
  Uri root, zipFile; String docId,zipName="",prefix="";
  boolean inZip=false, hidden=false, busy=false;
  final LinkedHashSet<String> selected=new LinkedHashSet<>();
  TextView path,status; ListView list; Button extract, moveButton, packButton;
  android.content.SharedPreferences prefs;
  String sortMode="name", search=""; boolean reverse=false, includeContainer=false;
  final ArrayList<String> history=new ArrayList<>();
  final LinkedHashSet<String> favorites=new LinkedHashSet<>();
  boolean cancelTask=false; TextView progressText;
  Uri lastDestination;
  boolean extractSelectedOnly=false;
  int compressionLevel=6;
  String archiveOutputName="";
  final ArrayDeque<String> forwardDocs=new ArrayDeque<>();
  final ArrayDeque<String> forwardNames=new ArrayDeque<>();
  final ArrayList<String> operationLog=new ArrayList<>();
  final ArrayList<String> searchResults=new ArrayList<>();
  long minFilterSize=-1,maxFilterSize=-1,afterFilter=0,beforeFilter=0;
  String extensionFilter="";
  boolean onlyFolders=false,onlyFiles=false;
  final static int MAX_SEARCH_RESULTS=1200;
  // 2.4: duas abas independentes e área de transferência (sem acesso irrestrito).
  static class TabState {
    Uri tree; String currentId; boolean zipMode=false; Uri archive; String archiveName="",archivePrefix="";
    final ArrayList<String> names=new ArrayList<>(),ids=new ArrayList<>();
    final ArrayList<ZItem> entries=new ArrayList<>();
  }
  final TabState[] tabs={new TabState(),new TabState()};
  int activeTab=0;
  Button tabA,tabB;
  Uri clipboardTree;String clipboardParentId;final ArrayList<Row> clipboardRows=new ArrayList<>();
  boolean clipboardCut=false;
  String clipboardLabel="";
  final ArrayList<String> recentPaths=new ArrayList<>();
  AdvancedTools advanced;
  LocalShare localShare;
  TransferManager transfers;
  BackupTools backups;
  SnapshotTools snapshots;
  RestoreTools restoreTools;
  FolderCompareTools folderCompareTools;
  SyncTools syncTools;
  DeltaBackupTools deltaBackupTools;
  Research36 research36;
  Gzip36 gzip36;
  Tools37 tools37;
  boolean darkUi37=false;
  ScrollView toolsScroll;
  Button toolsToggle;




  static class Row {
    String name,id,note; boolean folder,zip; long size,modified; String mime;
    Row(String n,String i,String s,boolean f,boolean z){name=n;id=i;note=s;folder=f;zip=z;}
    public String toString(){return (folder?"📁 ":zip?"🗜 ":"📄 ")+name+"\n"+note;}
  }
  static class ZItem {
    String name; boolean folder; long size;
    ZItem(String n,boolean f,long s){name=n;folder=f;size=s;}
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);
    prefs=getSharedPreferences("exploradorzip_v2",MODE_PRIVATE);
    String currentUi37=prefs.getString("mode37","sistema");
    darkUi37="escuro".equals(currentUi37)||("sistema".equals(currentUi37)&&
      (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        ==android.content.res.Configuration.UI_MODE_NIGHT_YES);
    setTheme(darkUi37?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
    hidden=prefs.getBoolean("hidden",true);
    try{org.json.JSONArray log=new org.json.JSONArray(prefs.getString("oplog","[]"));for(int j=0;j<log.length();j++)operationLog.add(log.getString(j));}catch(Exception ignored){}

    sortMode=prefs.getString("sort","name");reverse=prefs.getBoolean("reverse",false);compressionLevel=prefs.getInt("compressionLevel",6);
    try{org.json.JSONArray f=new org.json.JSONArray(prefs.getString("favorites","[]"));for(int j=0;j<f.length();j++)favorites.add(f.getString(j));}catch(Exception ignored){}

    LinearLayout base=new LinearLayout(this);base.setOrientation(1);base.setPadding(10,7,10,7);base.setBackgroundColor(darkUi37?0xff101827:0xfff6f8fb);
    LinearLayout quickAccess=new LinearLayout(this);quickAccess.setOrientation(0);
    toolsToggle=button("☰ Ferramentas");Button openFolderQuick=button("📁 Pasta"),openZipQuick=button("🗜 ZIP"),backQuick=button("←");
    quickAccess.addView(toolsToggle,new LinearLayout.LayoutParams(0,-2,2));
    quickAccess.addView(openFolderQuick,new LinearLayout.LayoutParams(0,-2,1));
    quickAccess.addView(openZipQuick,new LinearLayout.LayoutParams(0,-2,1));
    quickAccess.addView(backQuick,new LinearLayout.LayoutParams(0,-2,1));base.addView(quickAccess);
    openFolderQuick.setOnClickListener(v->chooseFolder(FOLDER));openZipQuick.setOnClickListener(v->chooseZip());backQuick.setOnClickListener(v->goBack());
    toolsScroll=new ScrollView(this);toolsScroll.setFillViewport(false);
    LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);
    toolsScroll.addView(panel);
    int toolsHeight=(int)(220*getResources().getDisplayMetrics().density+0.5f);
    base.addView(toolsScroll,new LinearLayout.LayoutParams(-1,toolsHeight));
    toolsScroll.setVisibility(prefs.getBoolean("tools_open",false)?View.VISIBLE:View.GONE);
    toolsToggle.setOnClickListener(v->{boolean open=toolsScroll.getVisibility()!=View.VISIBLE;toolsScroll.setVisibility(open?View.VISIBLE:View.GONE);prefs.edit().putBoolean("tools_open",open).apply();});
    TextView heading=new TextView(this);heading.setText("Explorador ZIP  ·  Offline");heading.setTextSize(22);heading.setTextColor(darkUi37?0xffedf4ff:0xff172b48);panel.addView(heading);
    LinearLayout tabBar=new LinearLayout(this);tabBar.setOrientation(0);
    tabA=button("ABA A");tabB=button("ABA B");Button paste=button("Colar →");
    tabBar.addView(tabA,new LinearLayout.LayoutParams(0,-2,1));
    tabBar.addView(tabB,new LinearLayout.LayoutParams(0,-2,1));
    tabBar.addView(paste,new LinearLayout.LayoutParams(0,-2,1));panel.addView(tabBar);
    tabA.setOnClickListener(v->switchTab(0));tabB.setOnClickListener(v->switchTab(1));
    paste.setOnClickListener(v->pasteClipboard());
    LinearLayout bar=new LinearLayout(this);bar.setOrientation(0);
    Button folder=button("📁 Pasta"),z=button("🗜 ZIP"),back=button("← Voltar");
    bar.addView(folder,new LinearLayout.LayoutParams(0,-2,1));bar.addView(z,new LinearLayout.LayoutParams(0,-2,1));bar.addView(back,new LinearLayout.LayoutParams(0,-2,1));panel.addView(bar);
    folder.setOnClickListener(v->chooseFolder(FOLDER));z.setOnClickListener(v->chooseZip());back.setOnClickListener(v->goBack());
    CheckBox check=new CheckBox(this);check.setText("Mostrar ocultos (.github, .gitignore...)");
    check.setChecked(hidden);
    check.setOnCheckedChangeListener((v,on)->{hidden=on;prefs.edit().putBoolean("hidden",on).apply();refresh();});panel.addView(check);
    LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);
    Button tools=button("☰ Arquivos"), archive=button("🗜 ZIP+"), places=button("★ Locais");
    nav.addView(tools,new LinearLayout.LayoutParams(0,-2,1));nav.addView(archive,new LinearLayout.LayoutParams(0,-2,1));nav.addView(places,new LinearLayout.LayoutParams(0,-2,1));panel.addView(nav);
    tools.setOnClickListener(v->showOperations());archive.setOnClickListener(v->showArchiveTools());places.setOnClickListener(v->showLocations());
    LinearLayout extras=new LinearLayout(this);extras.setOrientation(0);
    Button organize=button("⚙ Organizar");extras.addView(organize,new LinearLayout.LayoutParams(0,-2,1));organize.setOnClickListener(v->organizerMenu());
    Button advancedButton=button("🛡 Auditoria 2.7");extras.addView(advancedButton,new LinearLayout.LayoutParams(0,-2,1));panel.addView(extras);
    advanced=new AdvancedTools(this); advancedButton.setOnClickListener(v->advanced.showMenu());
    localShare=new LocalShare(this);
    transfers=new TransferManager(this);
    backups=new BackupTools(this);
    snapshots=new SnapshotTools(this);
    restoreTools=new RestoreTools(this);
    folderCompareTools=new FolderCompareTools(this);
    syncTools=new SyncTools(this);
    deltaBackupTools=new DeltaBackupTools(this);
    research36=new Research36(this);
    gzip36=new Gzip36(this);
    tools37=new Tools37(this);
    Button extras37=button("✦ ZIP · PWA · Inventário · Aparência 3.7");
    panel.addView(extras37);
    extras37.setOnClickListener(v->tools37.menu());
    Button researchBtn=button("🔎 Conteúdo, filtros e ajustes 3.6");
    panel.addView(researchBtn);
    researchBtn.setOnClickListener(v->research36.menu());
    Button gzipBtn=button("🗜 GZIP 3.6 · compactar e extrair");
    panel.addView(gzipBtn);
    gzipBtn.setOnClickListener(v->gzip36.menu());
    LinearLayout shareBar=new LinearLayout(this);
    Button shareButton=button("⇧ Compartilhar"), lanButton=button("📶 Wi-Fi local");
    shareBar.addView(shareButton,new LinearLayout.LayoutParams(0,-2,1));
    shareBar.addView(lanButton,new LinearLayout.LayoutParams(0,-2,1));
    panel.addView(shareBar);
    Button transferButton=button("⇄ Transferências 2.9 · fila, progresso e cancelar");
    panel.addView(transferButton);
    transferButton.setOnClickListener(v->transfers.openMenu());
    Button backupButton=button("💾 Backups 3.0 · criar e verificar");
    panel.addView(backupButton);
    backupButton.setOnClickListener(v->backups.menu());
    Button snapshotButton=button("🧾 Snapshots 3.1 · comparar backups");
    panel.addView(snapshotButton);
    snapshotButton.setOnClickListener(v->snapshots.menu());
    Button restoreButton=button("♻ Restaurar 3.2 · seleção e proteção");
    panel.addView(restoreButton);
    restoreButton.setOnClickListener(v->restoreTools.menu());
    Button compareButton=button("🔍 Comparar pastas 3.3 · somente leitura");
    panel.addView(compareButton);
    compareButton.setOnClickListener(v->folderCompareTools.menu());
    Button syncButton=button("⇄ Sincronizar 3.4 · copiar apenas ausentes");
    panel.addView(syncButton);
    syncButton.setOnClickListener(v->syncTools.menu());
    Button deltaButton=button("💾 Backup incremental 3.5 · comparar e salvar ZIP");
    panel.addView(deltaButton);
    deltaButton.setOnClickListener(v->deltaBackupTools.menu());
    shareButton.setOnClickListener(v->shareSelectedAndroid());
    lanButton.setOnClickListener(v->showLanMenu());
    LinearLayout filters=new LinearLayout(this);
    Button searchButton=button("🔎 Buscar"),sortButton=button("↕ Ordem"),selectButton=button("☑ Seleção");
    filters.addView(searchButton,new LinearLayout.LayoutParams(0,-2,1));filters.addView(sortButton,new LinearLayout.LayoutParams(0,-2,1));filters.addView(selectButton,new LinearLayout.LayoutParams(0,-2,1));panel.addView(filters);
    searchButton.setOnClickListener(v->searchMenu());sortButton.setOnClickListener(v->sortDialog());selectButton.setOnClickListener(v->selectDialog());
    LinearLayout quickNav=new LinearLayout(this);
    Button forward=button("→ Avançar"), zipInfo=button("ZIP: verificar"), zipPreview=button("ZIP: prévia");
    quickNav.addView(forward,new LinearLayout.LayoutParams(0,-2,1));quickNav.addView(zipInfo,new LinearLayout.LayoutParams(0,-2,1));quickNav.addView(zipPreview,new LinearLayout.LayoutParams(0,-2,1));panel.addView(quickNav);
    forward.setOnClickListener(v->goForward());zipInfo.setOnClickListener(v->checkZipIntegrity());zipPreview.setOnClickListener(v->previewZip());
    path=new TextView(this);path.setTextColor(darkUi37?0xff80e0e4:0xff275786);path.setPadding(2,8,2,8);base.addView(path);
    list=new ListView(this);base.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((a,v,pos,id)->openRow(pos));
    list.setOnItemLongClickListener((a,v,pos,id)->{toggleSelection(pos);return true;});
    LinearLayout actions=new LinearLayout(this);
    moveButton=button("Mover selecionados");packButton=button("Compactar ZIP");
    actions.addView(moveButton,new LinearLayout.LayoutParams(0,-2,1));
    actions.addView(packButton,new LinearLayout.LayoutParams(0,-2,1));base.addView(actions);
    moveButton.setOnClickListener(v->startMove());packButton.setOnClickListener(v->startPack());
    extract=button("Extrair ZIP em nova pasta...");base.addView(extract);
    extract.setOnClickListener(v->{
      if(!inZip)return;
      String[] opts={"Extrair todo o ZIP", "Extrair somente entradas selecionadas (toque longo)"};
      new AlertDialog.Builder(this).setTitle("Extração segura")
        .setItems(opts,(d,w)->{
          extractSelectedOnly=(w==1);
          if(extractSelectedOnly&&selected.isEmpty()){fail("Selecione arquivos ou pastas do ZIP com toque longo");return;}
          chooseFolder(EXTRACT);
        }).setNegativeButton("Cancelar",null).show();
    });
    progressText=new TextView(this);progressText.setTextSize(12);base.addView(progressText);
    status=new TextView(this);status.setTextColor(darkUi37?0xffd0dbea:Color.DKGRAY);status.setPadding(0,9,0,0);base.addView(status);
    setContentView(base);
    try{org.json.JSONArray j=new org.json.JSONArray(prefs.getString("recentpaths","[]"));for(int k=0;k<j.length();k++)recentPaths.add(j.getString(k));}catch(Exception ignored){}
    if(!restoreLastFolder())render("Escolha uma pasta ou um ZIP. Pressione e segure para selecionar.");
    updateTabLabels();
  }
  void updateTabLabels(){
    if(tabA==null||tabB==null)return;
    tabA.setText((activeTab==0?"● ":"○ ")+"A "+(activeTab==0&&root!=null?"📁":tabs[0].tree!=null?"📁":""));
    tabB.setText((activeTab==1?"● ":"○ ")+"B "+(activeTab==1&&root!=null?"📁":tabs[1].tree!=null?"📁":""));
  }
  void rememberRecent(){
    if(root==null||docId==null||inZip)return;
    String loc="Aba "+(activeTab==0?"A":"B")+" • "+String.join("/",pathNames);
    recentPaths.remove(loc);recentPaths.add(0,loc);
    while(recentPaths.size()>30)recentPaths.remove(recentPaths.size()-1);
    prefs.edit().putString("recentpaths",new org.json.JSONArray(recentPaths).toString()).apply();
  }
  void storeTab(){
    TabState t=tabs[activeTab];t.tree=root;t.currentId=docId;
    t.names.clear();t.names.addAll(pathNames);t.ids.clear();t.ids.addAll(pathIds);
    t.zipMode=inZip;t.archive=zipFile;t.archiveName=zipName;t.archivePrefix=prefix;
    t.entries.clear();t.entries.addAll(zipItems);
  }
  void switchTab(int target){
    if(busy){fail("Conclua a operação antes de trocar abas");return;}
    if(target==activeTab)return;
    storeTab();activeTab=target;TabState t=tabs[target];
    root=t.tree;docId=t.currentId;pathNames.clear();pathNames.addAll(t.names);
    pathIds.clear();pathIds.addAll(t.ids);inZip=t.zipMode;zipFile=t.archive;
    zipName=t.archiveName;prefix=t.archivePrefix;zipItems.clear();zipItems.addAll(t.entries);
    selected.clear();forwardDocs.clear();forwardNames.clear();rows.clear();updateTabLabels();
    if(inZip)showZip();else if(root!=null)refresh();else render("Aba vazia. Selecione uma pasta ou ZIP.");
  }
  void saveClipboard(boolean cut){
    if(inZip||root==null||selectedFiles().isEmpty()){fail("Selecione arquivos de uma pasta primeiro");return;}
    clipboardTree=root;clipboardParentId=docId;clipboardRows.clear();clipboardRows.addAll(selectedFiles());
    clipboardCut=cut;clipboardLabel=(cut?"Recortar: ":"Copiar: ")+clipboardRows.size()+" itens";
    selected.clear();render(clipboardLabel+" — abra a pasta de destino (inclusive na outra aba) e toque em Colar.");
  }
  void pasteClipboard(){
    if(clipboardRows.isEmpty()||clipboardTree==null){fail("Área de transferência vazia");return;}
    if(inZip||root==null||docId==null){fail("Abra a pasta de destino antes de colar");return;}
    final Uri sourceTree=clipboardTree,destTree=root;
    final String parentId=clipboardParentId,targetId=docId;
    final ArrayList<Row> targets=new ArrayList<>(clipboardRows);
    if(sourceTree.equals(destTree)&&parentId.equals(targetId)){
      fail("Origem e destino são a mesma pasta. Escolha outra pasta.");return;
    }
    final boolean cut=clipboardCut;
    new AlertDialog.Builder(this).setTitle(cut?"Mover por comando nativo":"Copiar por comando nativo")
      .setMessage(targets.size()+" item(ns) para a pasta atual? Depende do suporte do provedor Android. Não sobrescreve intencionalmente nem usa cópia+exclusão como alternativa ao movimento.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Executar",(d,w)->{
        if(busy)return;busy=true;render("Executando transferência...");
        worker.execute(()->{
          int completed=0;ArrayList<String> errors=new ArrayList<>();
          Uri sourceParent=DocumentsContract.buildDocumentUriUsingTree(sourceTree,parentId);
          Uri targetParent=DocumentsContract.buildDocumentUriUsingTree(destTree,targetId);
          for(Row r:targets){try{
            Uri origin=DocumentsContract.buildDocumentUriUsingTree(sourceTree,r.id);
            Uri done=cut?DocumentsContract.moveDocument(getContentResolver(),origin,sourceParent,targetParent):DocumentsContract.copyDocument(getContentResolver(),origin,targetParent);
            if(done==null)throw new IOException("Operação não suportada pelo provedor");completed++;
          }catch(Exception e){errors.add(r.name+": "+e.getMessage());}}
          final int count=completed;final String problem=String.join("\n",errors);
          runOnUiThread(()->{busy=false;
            if(cut&&count==targets.size()){clipboardRows.clear();clipboardLabel="";}
            addLog((cut?"Recortar/colar: ":"Copiar/colar: ")+count+"/"+targets.size());
            refresh();new AlertDialog.Builder(this).setTitle("Transferência concluída")
              .setMessage(count+" de "+targets.size()+" concluído(s)"+(problem.isEmpty()?"":"\nFalhas:\n"+problem))
              .setPositiveButton("OK",null).show();
          });
        });
      }).show();
  }
  Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(prefs==null?12:prefs.getInt("font37",12));b.setAllCaps(false);return b;}
  void chooseFolder(int request){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,request);}
  void chooseZip(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,ZIP);}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri u=data.getData();try{
      if(request==INVENTORY37_CSV){if(tools37!=null)tools37.csvDestination(u);return;}
      if(request==CONFIG36_EXPORT||request==CONFIG36_IMPORT){if(research36!=null)research36.result(request,u);return;}
      if(request==BACKUP_DESTINATION){backups.destinationSelected(u,data);return;}
      if(request==BACKUP_VALIDATE){backups.validateSelected(u);return;}
      if(request==SNAPSHOT_A||request==SNAPSHOT_B){snapshots.selected(request,u);return;}
      if(request==RESTORE_ZIP||request==RESTORE_FOLDER){restoreTools.selected(request,u,data);return;}
      if(request==COMPARE_A||request==COMPARE_B){folderCompareTools.selected(request,u,data);return;}
      if(request==SYNC_SOURCE||request==SYNC_TARGET){syncTools.selected(request,u,data);return;}
      if(request==DELTA_SOURCE||request==DELTA_BASELINE||request==DELTA_OUTPUT){deltaBackupTools.selected(request,u,data);return;}
      if(request==EXPORT_REPORT){if(advanced!=null)advanced.writeLastReport(u);return;}
      if(request==FOLDER){
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(u,flags);
        root=u;docId=DocumentsContract.getTreeDocumentId(u);
        pathNames.clear();pathIds.clear();selected.clear();inZip=false;zipFile=null;forwardDocs.clear();forwardNames.clear();saveLastFolder();updateTabLabels();refresh();}
      if(request==ZIP){getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);loadZip(u,queryName(u));}
      if(request==EXTRACT)extractZip(u);
      if(request==MOVE)moveSelected(u);
      if(request==COPY){
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(flags!=0)getContentResolver().takePersistableUriPermission(u,flags);
        copySelected(u);
      }
      if(request==PACK){
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(flags!=0)getContentResolver().takePersistableUriPermission(u,flags);
        packSelected(u);
      }
    }catch(Exception e){fail(e.getMessage());}
  }
  String queryName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "arquivo.zip";}
  boolean passesFilters(String name,boolean dir,long size,long modified){
    String lower=name.toLowerCase(Locale.ROOT);
    if(!search.isEmpty()&&!lower.contains(search.toLowerCase(Locale.ROOT)))return false;
    if(!extensionFilter.isEmpty()&&!dir&&!lower.endsWith(extensionFilter.toLowerCase(Locale.ROOT)))return false;
    if(!extensionFilter.isEmpty()&&dir)return false;
    if(onlyFiles&&dir)return false;
    if(onlyFolders&&!dir)return false;
    if(minFilterSize>=0&&(size<0||size<minFilterSize))return false;
    if(maxFilterSize>=0&&(size<0||size>maxFilterSize))return false;
    if(afterFilter>0&&(modified<=0||modified<afterFilter))return false;
    if(beforeFilter>0&&(modified<=0||modified>beforeFilter))return false;
    return true;
  }
  void addLog(String event){
    operationLog.add(0,dateString(System.currentTimeMillis())+" — "+event);
    while(operationLog.size()>150)operationLog.remove(operationLog.size()-1);
    prefs.edit().putString("oplog",new org.json.JSONArray(operationLog).toString()).apply();
  }
  void render(String s){updateTabLabels();path.setText(inZip?"ZIP: "+zipName+" / "+prefix:root==null?"Nenhuma pasta selecionada":"Pasta / "+String.join(" / ",pathNames));
    extract.setVisibility(inZip?View.VISIBLE:View.GONE);
    moveButton.setVisibility(inZip?View.GONE:View.VISIBLE);
    packButton.setVisibility(inZip?View.GONE:View.VISIBLE);
    moveButton.setEnabled(!busy&&!selected.isEmpty());packButton.setEnabled(!busy&&!selected.isEmpty());
    status.setText(s+(selected.isEmpty()?"":" • "+selected.size()+" selecionado(s)"));
    ArrayList<String> labels=new ArrayList<>();for(Row r:rows)labels.add((selected.contains(r.id)?"☑ ":"    ")+r.toString());list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));
  }
  void refresh(){if(inZip){showZip();return;}if(root==null||docId==null){rows.clear();render("Abra uma pasta ou ZIP.");return;}if(busy)return;
    busy=true;status.setText("Lendo pasta...");Uri snapshot=root;String id=docId;
    worker.execute(()->{ArrayList<Row> out=new ArrayList<>();String err=null;
      try{Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(snapshot,id);
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=getContentResolver().query(children,projection,null,null,null)){
          if(c==null)throw new IOException("Pasta indisponível");
          while(c.moveToNext()){String entryId=c.getString(0),name=c.getString(1),mime=c.getString(2);long size=c.isNull(3)?-1:c.getLong(3);
            if(name==null||(!hidden&&name.startsWith(".")))continue;
            long modified=c.isNull(4)?-1:c.getLong(4);
            boolean isDir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            boolean isZip=!isDir&&name.toLowerCase(Locale.ROOT).endsWith(".zip");
            if(!passesFilters(name,isDir,size,modified))continue;
            Row entry=new Row(name,entryId,isDir?"Pasta":(isZip?"ZIP · ":"")+sizeString(size)+" · "+dateString(modified),isDir,isZip);
            entry.size=size;entry.modified=modified;entry.mime=mime;out.add(entry);
          }
        }
        sortRows(out);
      }catch(Exception e){err=e.getMessage();}
      String msg=err;runOnUiThread(()->{busy=false;if(inZip||!snapshot.equals(root)||!id.equals(docId))return;rows.clear();rows.addAll(out);render(msg==null?out.size()+" itens":"Erro: "+msg);});
    });
  }
  void sortRows(ArrayList<Row> a){a.sort((x,y)->{
    if(x.folder!=y.folder)return x.folder?-1:1;
    int cmp=0;
    switch(sortMode){case "size":cmp=Long.compare(x.size,y.size);break;
      case "date":cmp=Long.compare(x.modified,y.modified);break;
      case "type":cmp=String.valueOf(x.mime).compareToIgnoreCase(String.valueOf(y.mime));break;
      default:cmp=x.name.compareToIgnoreCase(y.name);}
    if(cmp==0)cmp=x.name.compareToIgnoreCase(y.name);
    return reverse?-cmp:cmp;
  });}
  static String dateString(long n){return n<=0?"sem data":new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(n));}
  void openRow(int pos){if(busy||pos<0||pos>=rows.size())return;Row r=rows.get(pos);
    if(inZip){if(r.folder){prefix=r.id;selected.clear();showZip();}else new AlertDialog.Builder(this).setTitle(r.name).setMessage("Arquivo dentro do ZIP.\n"+r.note+"\n\nNenhuma extração foi feita.").setPositiveButton("OK",null).show();}
    else if(r.folder){history.add("/"+String.join("/",pathNames));forwardDocs.clear();forwardNames.clear();pathNames.add(r.name);pathIds.add(r.id);docId=r.id;selected.clear();saveLastFolder();refresh();}
    else if(r.zip)loadZip(DocumentsContract.buildDocumentUriUsingTree(root,r.id),r.name);
    else fileActions(r);
  }
  void loadZip(Uri uri,String name){if(busy)return;busy=true;status.setText("Lendo ZIP sem extrair...");
    worker.execute(()->{ArrayList<ZItem> out=new ArrayList<>();String err=null;
      try(InputStream input=getContentResolver().openInputStream(uri)){
        if(input==null)throw new IOException("Acesso ao arquivo negado");
        try(ZipInputStream zip=new ZipInputStream(input)){
          ZipEntry e;int count=0;while((e=zip.getNextEntry())!=null){if(++count>12000)throw new IOException("Mais de 12 mil entradas no ZIP");String entry=e.getName().replace('\\','/');
            while(entry.startsWith("./"))entry=entry.substring(2);
            if(!entry.isEmpty())out.add(new ZItem(entry,e.isDirectory(),e.getSize()));zip.closeEntry();
          }
        }
      }catch(Exception e){err=e.getMessage();}
      String msg=err;runOnUiThread(()->{busy=false;if(msg!=null){fail("Não foi possível ler ZIP: "+msg);return;}zipFile=uri;zipName=name;selected.clear();zipItems.clear();zipItems.addAll(out);prefix="";inZip=true;showZip();});
    });
  }
  void showZip(){if(!inZip)return;Map<String,Row> opts=new HashMap<>();
    for(ZItem z:zipItems){if(!z.name.startsWith(prefix)||z.name.equals(prefix))continue;
      String rest=z.name.substring(prefix.length());int slash=rest.indexOf('/');
      String seg=slash<0?rest:rest.substring(0,slash);
      if(seg.isEmpty()||(!hidden&&seg.startsWith(".")))continue;
      boolean folder=slash>=0||z.folder;
      opts.put(seg,new Row(seg,folder?prefix+seg+"/":z.name,folder?"Pasta interna":sizeString(z.size),folder,false));
    }
    rows.clear();rows.addAll(opts.values());sortRows(rows);render(rows.size()+" itens • "+zipItems.size()+" entradas no ZIP (toque longo seleciona)");
  }
  void goBack(){if(busy)return;
    if(inZip){if(!prefix.isEmpty()){String p=prefix.substring(0,prefix.length()-1);int i=p.lastIndexOf('/');prefix=i<0?"":p.substring(0,i+1);selected.clear();showZip();}
      else {inZip=false;zipFile=null;zipItems.clear();refresh();}}
    else if(!pathNames.isEmpty()){
      forwardDocs.push(pathIds.get(pathIds.size()-1));forwardNames.push(pathNames.get(pathNames.size()-1));
      pathNames.remove(pathNames.size()-1);pathIds.remove(pathIds.size()-1);docId=pathIds.isEmpty()?DocumentsContract.getTreeDocumentId(root):pathIds.get(pathIds.size()-1);selected.clear();saveLastFolder();refresh();}
    else Toast.makeText(this,"Você está na pasta inicial",Toast.LENGTH_SHORT).show();
  }
  void goForward(){
    if(inZip||forwardDocs.isEmpty()||root==null){fail("Nenhuma pasta seguinte no histórico");return;}
    docId=forwardDocs.pop();pathIds.add(docId);pathNames.add(forwardNames.pop());selected.clear();saveLastFolder();refresh();
  }
  boolean selectedZipEntry(String entry){
    if(!extractSelectedOnly)return true;
    for(String key:selected)if(entry.equals(key)||entry.startsWith(key.endsWith("/")?key:key+"/"))return true;
    return false;
  }
  void previewZip(){
    if(!inZip){fail("Primeiro abra um ZIP");return;}
    int dirs=0,files=0,hiddenEntries=0;long size=0;
    StringBuilder names=new StringBuilder();
    for(ZItem z:zipItems){if(z.folder)dirs++;else files++;if(z.name.startsWith(".")||z.name.contains("/."))hiddenEntries++;
      if(z.size>0)size+=z.size;if(names.length()<4500)names.append(z.folder?"📁 ":"📄 ").append(z.name).append('\n');}
    new AlertDialog.Builder(this).setTitle("Prévia de "+zipName)
      .setMessage("Arquivos: "+files+"\nPastas: "+dirs+"\nEntradas ocultas: "+hiddenEntries+"\nSoma de tamanhos conhecidos: "+sizeString(size)+"\n\n"+names)
      .setPositiveButton("Fechar",null).show();
  }
  void checkZipIntegrity(){
    if(!inZip||zipFile==null){fail("Primeiro abra um ZIP");return;}
    final Uri u=zipFile;busy=true;status.setText("Verificando integridade CRC, lendo ZIP inteiro...");
    worker.execute(()->{
      String failure=null;int count=0;long total=0;
      try(InputStream input=getContentResolver().openInputStream(u)){
        if(input==null)throw new IOException("Não foi possível ler ZIP");
        try(ZipInputStream z=new ZipInputStream(new BufferedInputStream(input))){
          ZipEntry e;byte[] buf=new byte[32768];
          while((e=z.getNextEntry())!=null){
            if(++count>12000)throw new IOException("Limite de 12 mil entradas");
            int n;while((n=z.read(buf))!=-1){total+=n;if(total>1024L*1024*1024)throw new IOException("Limite de 1 GB na verificação");}
            z.closeEntry();
          }
        }
      }catch(Exception e){failure=e.getMessage();}
      final String error=failure;final int files=count;final long bytes=total;
      runOnUiThread(()->{busy=false;render(error==null?"ZIP verificado":"Falha de leitura do ZIP");
        new AlertDialog.Builder(this).setTitle(error==null?"Verificação concluída":"Verificação interrompida")
          .setMessage(error==null?"Lidas "+files+" entradas, "+sizeString(bytes)+" descompactados. A leitura terminou sem erro detectado pelo decodificador ZIP. Isto não autentica a origem do arquivo.":error)
          .setPositiveButton("OK",null).show();
      });
    });
  }
  void githubZipAudit(){
    if(!inZip){fail("Abra um ZIP de projeto Android primeiro");return;}
    String[] required={"app/build.gradle","build.gradle","settings.gradle","gradle.properties",".github/workflows/main.yml"};
    Set<String> entries=new HashSet<>();for(ZItem z:zipItems)entries.add(z.name);
    StringBuilder result=new StringBuilder();int ok=0;
    for(String r:required){boolean has=entries.contains(r);if(has)ok++;result.append(has?"✅ ":"⚠ ").append(r).append('\n');}
    int nested=0;for(ZItem z:zipItems){if(z.name.matches("[^/]+/app/build\\.gradle"))nested++;}
    result.append("\nArquivos encontrados: ").append(ok).append("/").append(required.length);
    if(nested>0)result.append("\n⚠ Possível pasta principal envolvendo o projeto.");
    result.append("\n\nAnálise estrutural apenas; não garante compilação.");
    new AlertDialog.Builder(this).setTitle("Auditoria ZiptoGit").setMessage(result).setPositiveButton("OK",null).show();
  }
  static String sizeString(long n){if(n<0)return "Tamanho desconhecido";if(n<1024)return n+" B";return String.format(Locale.ROOT,n<1048576?"%.1f KB":"%.1f MB",n<(1048576)?n/1024.0:n/1048576.0);}
  void fail(String s){status.setText(s);Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  @Override public void onBackPressed(){goBack();}
  @Override protected void onDestroy(){if(localShare!=null)localShare.stop();if(transfers!=null)transfers.close();if(advanced!=null)advanced.cancel();if(research36!=null)research36.close();if(tools37!=null)tools37.close();worker.shutdownNow();super.onDestroy();}
  void shareSelectedAndroid(){
    if(inZip||root==null){fail("Abra uma pasta comum e selecione arquivos");return;}
    ArrayList<Row> selection=selectedFiles();
    ArrayList<Uri> out=new ArrayList<>();
    for(Row r:selection)if(!r.folder)out.add(DocumentsContract.buildDocumentUriUsingTree(root,r.id));
    if(out.isEmpty()){fail("Selecione ao menos um arquivo (toque longo)");return;}
    Intent i=new Intent(out.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
    i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if(out.size()==1)i.putExtra(Intent.EXTRA_STREAM,out.get(0));
    else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM,out);
    android.content.ClipData cd=android.content.ClipData.newUri(getContentResolver(),"Arquivo",out.get(0));
    for(int x=1;x<out.size();x++)cd.addItem(new android.content.ClipData.Item(out.get(x)));
    i.setClipData(cd);
    try{startActivity(Intent.createChooser(i,"Enviar arquivos com..."));}catch(Exception e){fail("Não foi possível compartilhar: "+e.getMessage());}
  }
  void showLanMenu(){
    if(localShare==null)return;
    if(localShare.isRunning()){
      new AlertDialog.Builder(this).setTitle("Transferência Wi-Fi (somente leitura)")
        .setMessage("Servidor ativo na rede local.\n\n"+localShare.getLink()+"\n\nNão use em redes públicas. O link inclui uma chave temporária. Qualquer pessoa que obtiver esse link na mesma rede poderá baixar os arquivos publicados.")
        .setPositiveButton("Copiar link",(d,w)->{
          ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Link Wi-Fi",localShare.getLink()));
          Toast.makeText(this,"Link copiado",Toast.LENGTH_SHORT).show();
        })
        .setNeutralButton("Parar servidor",(d,w)->{localShare.stop();fail("Compartilhamento Wi-Fi encerrado");})
        .setNegativeButton("Fechar",null).show();return;
    }
    if(inZip||root==null){fail("Selecione arquivos de uma pasta comum");return;}
    ArrayList<Row> chosen=selectedFiles(), files=new ArrayList<>();
    for(Row r:chosen)if(!r.folder)files.add(r);
    if(files.isEmpty()){fail("Pressione e segure os arquivos que deseja disponibilizar");return;}
    final Uri tree=root;
    new AlertDialog.Builder(this).setTitle("Publicar "+files.size()+" arquivo(s) por Wi-Fi")
      .setMessage("Somente os arquivos selecionados. Transferência somente para download, sem envio ou edição.\n\nUse apenas uma rede local confiável. O link contém chave aleatória, mas o HTTP local NÃO é criptografado. Pare o servidor quando terminar; ele também é encerrado ao fechar a tela do app.")
      .setPositiveButton("Iniciar",(d,w)->{
        try{
          localShare.start(tree,files);
          showLanMenu();
        }catch(Exception e){fail("Não foi possível iniciar Wi-Fi local: "+e.getMessage());}
      }).setNegativeButton("Cancelar",null).show();
  }
  static boolean safe(String name){
    if(name.startsWith("/")||name.startsWith("\\")||name.contains(":")||name.indexOf('\0')>=0)return false;
    for(String segment:name.split("/"))if(segment.equals(".."))return false;
    return true;
  }
  void extractZip(Uri destination){if(busy||zipFile==null)return;
    Uri source=zipFile;String title=zipName;busy=true;status.setText("Extraindo... Não feche o aplicativo.");
    worker.execute(()->{
      String err=null;int files=0;long bytes=0;
      try{
        android.content.ContentResolver resolver=getContentResolver();
        Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination));
        String base=title.replaceAll("(?i)\\.zip$","").replaceAll("[^A-Za-z0-9._ -]","_");
        if(base.isEmpty()||base.equals(".")||base.equals(".."))base="arquivo";
        Uri output=DocumentsContract.createDocument(resolver,rootDoc,DocumentsContract.Document.MIME_TYPE_DIR,base+"_extraido_"+System.currentTimeMillis());
        if(output==null)throw new IOException("Não foi possível criar a pasta de destino");
        Map<String,Uri> made=new HashMap<>();made.put("",output);
        try(InputStream in=resolver.openInputStream(source)){
          if(in==null)throw new IOException("Não foi possível ler ZIP");
          try(ZipInputStream zip=new ZipInputStream(in)){
            byte[] buffer=new byte[32768];ZipEntry e;int entryCount=0;
            while((e=zip.getNextEntry())!=null){
              if(++entryCount>12000)throw new IOException("Limite de itens excedido");
              String name=e.getName().replace('\\','/');while(name.startsWith("./"))name=name.substring(2);
              if(name.isEmpty()){zip.closeEntry();continue;}
              if(!safe(name))throw new IOException("Caminho inseguro: "+name);
              if(!selectedZipEntry(name)){zip.closeEntry();continue;}
              String[] pieces=name.split("/");String parent="";
              int subCount=e.isDirectory()?pieces.length:pieces.length-1;
              for(int k=0;k<subCount;k++){
                if(pieces[k].isEmpty())continue;
                String child=parent+pieces[k]+"/";
                if(!made.containsKey(child)){
                  Uri newDir=DocumentsContract.createDocument(resolver,made.get(parent),DocumentsContract.Document.MIME_TYPE_DIR,pieces[k]);
                  if(newDir==null)throw new IOException("Erro criando pasta: "+child);
                  made.put(child,newDir);
                }
                parent=child;
              }
              if(!e.isDirectory()){
                String fileName=pieces[pieces.length-1];
                if(fileName.isEmpty())throw new IOException("Nome vazio");
                Uri file=DocumentsContract.createDocument(resolver,made.get(parent),"application/octet-stream",fileName);
                if(file==null)throw new IOException("Erro criando arquivo: "+name);
                try(OutputStream out=resolver.openOutputStream(file,"w")){
                  if(out==null)throw new IOException("Falha ao abrir arquivo de saída");
                  long fileBytes=0;int length;
                  while((length=zip.read(buffer))!=-1){
                    fileBytes+=length;bytes+=length;
                    if(fileBytes>150L*1024*1024||bytes>500L*1024*1024)throw new IOException("Limite de extração (150 MB por arquivo, 500 MB no total)");
                    out.write(buffer,0,length);
                  }
                }
                files++;
              }
              zip.closeEntry();
            }
          }
        }
      }catch(Exception e){err=e.getMessage();}
      final int count=files;final long total=bytes;final String message=err;
      runOnUiThread(()->{busy=false;
        if(message==null){render("Extraídos "+count+" arquivos ("+sizeString(total)+")");
          new AlertDialog.Builder(this).setTitle("Extração concluída").setMessage("Extraídos "+count+" arquivos numa nova subpasta. ZIP original preservado. Datas de arquivos extraídos podem mudar.").setPositiveButton("OK",null).show();}
        else{render("Falha: "+message);
          new AlertDialog.Builder(this).setTitle("Extração incompleta").setMessage(message+"\n\nUma pasta parcialmente extraída pode permanecer no destino.").setPositiveButton("OK",null).show();}
      });
    });
  }

  // O Android preserva a autorização da árvore; este método recupera também a subpasta atual.
  void saveLastFolder(){
    if(root==null||docId==null)return;
    rememberRecent();
    prefs.edit().putString("tree",root.toString()).putString("doc",docId)
      .putString("names",new org.json.JSONArray(pathNames).toString())
      .putString("ids",new org.json.JSONArray(pathIds).toString()).apply();
  }
  boolean restoreLastFolder(){
    String value=prefs.getString("tree",null);if(value==null)return false;
    try{
      Uri stored=Uri.parse(value);
      boolean granted=false;
      for(android.content.UriPermission perm:getContentResolver().getPersistedUriPermissions()){
        if(perm.getUri().equals(stored)&&perm.isReadPermission()){granted=true;break;}
      }
      if(!granted)return false;
      root=stored;docId=prefs.getString("doc",DocumentsContract.getTreeDocumentId(stored));
      org.json.JSONArray names=new org.json.JSONArray(prefs.getString("names","[]"));
      org.json.JSONArray ids=new org.json.JSONArray(prefs.getString("ids","[]"));
      pathNames.clear();pathIds.clear();
      for(int i=0;i<Math.min(names.length(),ids.length());i++){
        pathNames.add(names.getString(i));pathIds.add(ids.getString(i));
      }
      refresh();return true;
    }catch(Exception e){root=null;docId=null;return false;}
  }
  void toggleSelection(int pos){
    if(busy||pos<0||pos>=rows.size())return;
    Row r=rows.get(pos);

    if(!selected.add(r.id))selected.remove(r.id);
    render("Pressione e segure para marcar/desmarcar; toque normal para abrir.");
  }
  ArrayList<Row> selectedFiles(){
    ArrayList<Row> out=new ArrayList<>();
    for(Row r:rows)if(selected.contains(r.id))out.add(r);
    return out;
  }
  void startMove(){
    if(inZip||selectedFiles().isEmpty()){fail("Selecione arquivos com um toque prolongado.");return;}
    new AlertDialog.Builder(this).setTitle("Mover arquivos")
      .setMessage("Solicitaremos o MOVE nativo ao provedor Android. Não há fallback de copiar e apagar. A operação pode falhar se o provedor ou a pasta não permitirem mover. Arquivos de mesmo nome exigem cuidado.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Escolher pasta destino",(d,w)->chooseFolder(MOVE)).show();
  }
  void moveSelected(Uri destination){
    if(root==null||docId==null)return;
    final Uri sourceTree=root;final String parentId=docId;
    final ArrayList<Row> targets=selectedFiles();
    if(targets.isEmpty())return;
    final Uri parentDoc=DocumentsContract.buildDocumentUriUsingTree(sourceTree,parentId);
    final Uri destinationDoc=DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination));
    busy=true;render("Movendo arquivos...");
    worker.execute(()->{
      int moved=0;ArrayList<String> errors=new ArrayList<>();
      for(Row r:targets){
        try{
          Uri document=DocumentsContract.buildDocumentUriUsingTree(sourceTree,r.id);
          Uri result=DocumentsContract.moveDocument(getContentResolver(),document,parentDoc,destinationDoc);
          if(result==null)throw new IOException("MOVE não suportado pelo provedor");
          moved++;
        }catch(Exception e){errors.add(r.name+": "+e.getClass().getSimpleName()+" — "+e.getMessage());}
      }
      final int n=moved;final String problem=String.join("\n",errors);
      runOnUiThread(()->{
        busy=false;selected.clear();refresh();
        addLog("Mover: "+n+" de "+targets.size()+" concluído(s)");
        new AlertDialog.Builder(this).setTitle("Resultado da movimentação")
          .setMessage(n+" de "+targets.size()+" arquivo(s) movido(s) pelo pedido nativo."
            +(problem.isEmpty()?"":"\n\nFalhas:\n"+problem)
            +"\n\nAs datas dependem do provedor Android.")
          .setPositiveButton("OK",null).show();
      });
    });
  }
  void startPack(){
    if(inZip||selectedFiles().isEmpty()){fail("Selecione arquivos com um toque prolongado.");return;}
    prompt("Nome do ZIP (incluir .zip)","Projeto_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date())+".zip", value->{
      if(!validFilename(value)||!value.toLowerCase(Locale.ROOT).endsWith(".zip")){fail("Use um nome válido terminado em .zip");return;}
      archiveOutputName=value;
      new AlertDialog.Builder(this).setTitle("Criar ZIP")
        .setMessage("Compactar "+selectedFiles().size()+" item(ns), incluindo pastas e ocultos? Os originais permanecem intactos. Nível "+compressionLevel+".")
        .setNegativeButton("Cancelar",null).setPositiveButton("Escolher destino",(d,w)->chooseFolder(PACK)).show();
    });
  }
  void packSelected(Uri destination){
    if(root==null)return;
    final Uri tree=root;final ArrayList<Row> targets=selectedFiles();if(targets.isEmpty())return;
    final Uri targetDoc=DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination));
    busy=true;cancelTask=false;render("Criando ZIP...");
    worker.execute(()->{
      String err=null;int count=0;Uri created=null;
      try{
        String filename=archiveOutputName.isEmpty()?"Projeto_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date())+".zip":archiveOutputName;
        created=DocumentsContract.createDocument(getContentResolver(),targetDoc,"application/zip",filename);
        if(created==null)throw new IOException("Não foi possível criar ZIP no destino");
        try(OutputStream out=getContentResolver().openOutputStream(created,"w")){
          if(out==null)throw new IOException("Destino sem escrita");
          try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(out))){
            zip.setLevel(compressionLevel);long[] totals={0,0};Set<String> names=new HashSet<>();
            for(Row row:targets){
              Uri item=DocumentsContract.buildDocumentUriUsingTree(tree,row.id);
              String name=includeContainer?"Projeto/"+row.name:row.name;
              writeZipItem(zip,item,name,0,totals,names);
            }
            count=(int)totals[0];
          }
        }
      }catch(Exception e){err=e.getMessage();if(created!=null)try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}}
      final int n=count;final String error=err;
      runOnUiThread(()->{busy=false;selected.clear();refresh();
        addLog(error==null?"ZIP criado: "+archiveOutputName+" ("+n+" arquivos)":"Falha ao compactar: "+error);
        new AlertDialog.Builder(this).setTitle(error==null?"ZIP criado":"Falha ao compactar")
          .setMessage(error==null?n+" arquivo(s) incluído(s). Originais preservados. "+(includeContainer?"Com pasta principal.":"Sem pasta principal.")
            :error+"\nUm ZIP incompleto foi removido quando possível.")
          .setPositiveButton("OK",null).show();
      });
    });
  }
  void writeZipItem(ZipOutputStream zip,Uri doc,String name,int depth,long[] totals,Set<String> names)throws Exception{
    if(depth>35||totals[0]>25000||totals[1]>1024L*1024*1024)throw new IOException("Limites do ZIP excedidos");
    if(!safe(name)||name.contains("\\"))throw new IOException("Caminho inseguro: "+name);
    String mime=getMime(doc);
    boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    if(dir){
      String d=name.endsWith("/")?name:name+"/";
      if(names.add(d)){zip.putNextEntry(new ZipEntry(d));zip.closeEntry();}
      String id=DocumentsContract.getDocumentId(doc);
      Uri kids=DocumentsContract.buildChildDocumentsUriUsingTree(root,id);
      try(Cursor c=getContentResolver().query(kids,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
        if(c==null)throw new IOException("Não foi possível listar "+name);
        while(c.moveToNext()){
          String childName=c.getString(1),childId=c.getString(0);
          if(childName==null||childName.equals(".")||childName.equals("..")||childName.contains("/"))continue;
          writeZipItem(zip,DocumentsContract.buildDocumentUriUsingTree(root,childId),d+childName,depth+1,totals,names);
        }
      }
    }else{
      if(!names.add(name))throw new IOException("Entrada duplicada: "+name);
      try(InputStream in=getContentResolver().openInputStream(doc)){
        if(in==null)throw new IOException("Sem acesso: "+name);
        ZipEntry entry=new ZipEntry(name);zip.putNextEntry(entry);byte[] buf=new byte[32768];int n;
        while((n=in.read(buf))!=-1){totals[1]+=n;if(totals[1]>1024L*1024*1024)throw new IOException("Limite de 1 GB");zip.write(buf,0,n);}
        zip.closeEntry();totals[0]++;
      }
    }
  }
  String getMime(Uri u){
    try(Cursor c=getContentResolver().query(u,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
      if(c!=null&&c.moveToFirst())return c.getString(0);
    }catch(Exception ignored){}return "application/octet-stream";
  }

  // 2.6: ações de organização com prévia obrigatória e permissões SAF.
  void organizerMenu(){
    String[] options={
      "Organizar por extensão (prévia)", "Organizar por categoria de arquivo (prévia)",
      "Organizar por ano de modificação (prévia)", "Organizar por mês de modificação (prévia)",
      "Organizar por faixa de tamanho (prévia)", "Organizar por idade da modificação (prévia)",
      "Organizar apenas selecionados", "Organizar todos os arquivos visíveis",
      "Relatório: arquivos maiores", "Relatório: arquivos mais antigos",
      "Relatório: arquivos mais recentes", "Relatório: arquivos sem extensão",
      "Relatório: arquivos sem data conhecida", "Relatório: arquivos sem tamanho conhecido",
      "Relatório: arquivos vazios", "Relatório: arquivos ocultos visíveis",
      "Relatório: distribuição por categoria", "Relatório: distribuição por mês",
      "Relatório: faixas de tamanho", "Relatório: distribuição por ano",
      "Relatório: extensões mais frequentes", "Relatório: arquivos acima de um limite",
      "Relatório: arquivos anteriores a N dias", "Resumo do plano (sem alterações)"
    };
    new AlertDialog.Builder(this).setTitle("Organização e análise · 2.6")
      .setItems(options,(d,w)->{
        if(w<=5){organizerMode=w;previewOrganization(w,false);}
        else if(w==6){previewOrganization(organizerMode,true);}
        else if(w==7){previewOrganization(organizerMode,false);}
        else if(w>=8&&w<=20){reportOrganizer(w);}
        else if(w==21)prompt("Tamanho mínimo (MB)","100",value->{
          try{long min=Long.parseLong(value)*1048576L;reportFiltered("Arquivos acima de "+value+" MB",r->!r.folder&&r.size>=min);}catch(Exception e){fail("Número inválido");}
        });
        else if(w==22)prompt("Não modificados há N dias","365",value->{
          try{long days=Long.parseLong(value);if(days<0||days>36500)throw new Exception();long before=System.currentTimeMillis()-days*86400000L;
            reportFiltered("Arquivos antigos",r->!r.folder&&r.modified>0&&r.modified<before);
          }catch(Exception e){fail("Número inválido");}
        });
        else showReport("Organização 2.6","Modos disponíveis: extensão, categoria, ano, mês, tamanho e idade.\n"+
          "O aplicativo não move nada sem sua confirmação.\n"+
          "Somente arquivos da pasta atual são movidos; subpastas são preservadas.\n"+
          "Não há alternativa de copiar e excluir caso MOVE não seja suportado.\n"+
          "Pastas só são criadas após confirmar. Confira a prévia.");
      }).setNegativeButton("Fechar",null).show();
  }
  int organizerMode=1;
  interface RowPredicate {boolean test(Row r);}
  ArrayList<Row> organizerFiles(boolean selectedOnly){
    ArrayList<Row> out=new ArrayList<>();
    if(inZip||root==null)return out;
    for(Row r:rows)if(!r.folder&&(!selectedOnly||selected.contains(r.id)))out.add(r);
    return out;
  }
  static String normalizedExtension(String n){int dot=n.lastIndexOf('.');return dot<=0||dot==n.length()-1?"Sem_extensao":n.substring(dot+1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","_");}
  static String category(String n){String e=normalizedExtension(n);
    if(Arrays.asList("jpg","jpeg","png","gif","webp","bmp","heic","svg").contains(e))return "Imagens";
    if(Arrays.asList("mp4","mkv","mov","avi","webm","3gp").contains(e))return "Videos";
    if(Arrays.asList("mp3","m4a","wav","flac","ogg","aac").contains(e))return "Audios";
    if(Arrays.asList("pdf","doc","docx","odt","txt","rtf","md","epub").contains(e))return "Documentos";
    if(Arrays.asList("zip","rar","7z","tar","gz","bz2").contains(e))return "Compactados";
    if(Arrays.asList("apk","aab").contains(e))return "Aplicativos";
    if(Arrays.asList("html","htm","css","js","ts","json","xml","yaml","yml","java","kt","py","gradle","sh").contains(e))return "Codigos";
    if(Arrays.asList("csv","xls","xlsx","ods").contains(e))return "Planilhas";
    return "Outros";
  }
  String groupFor(Row r,int mode){
    if(mode==0)return "Ext_"+normalizedExtension(r.name);
    if(mode==1)return category(r.name);
    if(mode==2||mode==3){if(r.modified<=0)return "Sem_data";String format=mode==2?"yyyy":"yyyy-MM";return new SimpleDateFormat(format,Locale.ROOT).format(new Date(r.modified));}
    if(mode==4){if(r.size<0)return "Tamanho_desconhecido";if(r.size==0)return "Vazios";if(r.size<1048576)return "Ate_1MB";if(r.size<104857600)return "De_1_a_100MB";return "Acima_100MB";}
    if(r.modified<=0)return "Sem_data";
    long age=System.currentTimeMillis()-r.modified;
    if(age<7L*86400000)return "Ultimos_7_dias";
    if(age<30L*86400000)return "Ultimos_30_dias";
    if(age<365L*86400000)return "Ultimo_ano";return "Mais_de_1_ano";
  }
  // Lista os filhos do diretório, inclusive ocultos. Evita criar pasta/arquivo com nome já existente.
  HashMap<String,Uri> immediateChildren(Uri tree,String id)throws Exception{
    HashMap<String,Uri> result=new HashMap<>();
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
    try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c==null)throw new IOException("Não foi possível consultar o destino");
      while(c.moveToNext()){
        String childId=c.getString(0),name=c.getString(1);
        if(name!=null)result.put(name.toLowerCase(Locale.ROOT),DocumentsContract.buildDocumentUriUsingTree(tree,childId));
      }
    }
    return result;
  }
  void previewOrganization(int mode,boolean selectedOnly){
    if(inZip||root==null){fail("Abra uma pasta normal antes de organizar");return;}
    ArrayList<Row> items=organizerFiles(selectedOnly);
    if(items.isEmpty()){fail("Nenhum arquivo para organizar (verifique filtros e seleção)");return;}
    if(items.size()>1000){fail("Limite por operação: 1000 arquivos visíveis");return;}
    final Uri sourceTree=root;final String parentId=docId;
    final int chosenMode=mode;
    LinkedHashMap<String,ArrayList<Row>> plan=new LinkedHashMap<>();
    for(Row r:items)plan.computeIfAbsent(groupFor(r,mode),k->new ArrayList<>()).add(r);
    StringBuilder b=new StringBuilder("Somente arquivos visíveis na pasta atual: "+items.size()+"\n"+
      "Não organiza subpastas; filtros e ocultos afetam a lista.\n"+
      "Mover requer suporte MOVE do provedor. Não há cópia+exclusão.\n\n");
    for(Map.Entry<String,ArrayList<Row>> entry:plan.entrySet()){
      b.append("📁 ").append(entry.getKey()).append(" ("+entry.getValue().size()+")\n");
      for(Row r:entry.getValue())if(b.length()<12000)b.append("  ").append(r.name).append("\n");
    }
    if(b.length()>=12000)b.append("\n[Prévia truncada]");
    new AlertDialog.Builder(this).setTitle("Prévia · "+items.size()+" arquivos")
      .setMessage(b.toString()).setNegativeButton("Cancelar",null)
      .setNeutralButton("Copiar prévia",(d,w)->copyText("Plano de organização",b.toString()))
      .setPositiveButton("CONFIRMAR MOVIMENTAÇÃO",(d,w)->executeOrganization(sourceTree,parentId,plan,chosenMode)).show();
  }
  void executeOrganization(Uri tree,String parentId,LinkedHashMap<String,ArrayList<Row>> plan,int mode){
    if(busy||root==null||inZip||!root.equals(tree)||!docId.equals(parentId)){
      fail("A pasta mudou; refaça a prévia");return;
    }
    busy=true;render("Movimentando arquivos...");
    worker.execute(()->{
      int moved=0,errors=0;StringBuilder report=new StringBuilder();
      try{
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,parentId);
        HashMap<String,Uri> children=immediateChildren(tree,parentId);
        for(Map.Entry<String,ArrayList<Row>> entry:plan.entrySet()){
          String folderName=entry.getKey();Uri target=children.get(folderName.toLowerCase(Locale.ROOT));
          if(target!=null){
            // Verificar se o nome existente é de fato uma pasta.
            try(Cursor c=getContentResolver().query(target,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
              if(c==null||!c.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0))){
                errors+=entry.getValue().size();report.append(folderName).append(": nome ocupado por arquivo\n");continue;
              }
            }
          }else{
            try{target=DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,folderName);
              if(target==null)throw new IOException("Não foi possível criar pasta");
            }catch(Exception e){errors+=entry.getValue().size();report.append(folderName).append(": ").append(e.getMessage()).append('\n');continue;}
          }
          String targetId=DocumentsContract.getDocumentId(target);
          HashMap<String,Uri> occupied;
          try{occupied=immediateChildren(tree,targetId);}catch(Exception e){errors+=entry.getValue().size();report.append(folderName).append(": ").append(e.getMessage()).append('\n');continue;}
          for(Row r:entry.getValue()){
            if(occupied.containsKey(r.name.toLowerCase(Locale.ROOT))){errors++;report.append("Conflito, ignorado: ").append(r.name).append('\n');continue;}
            try{
              Uri origin=DocumentsContract.buildDocumentUriUsingTree(tree,r.id);
              Uri result=DocumentsContract.moveDocument(getContentResolver(),origin,parent,target);
              if(result==null)throw new IOException("MOVE não suportado");
              moved++;occupied.put(r.name.toLowerCase(Locale.ROOT),result);
            }catch(Exception e){errors++;report.append(r.name).append(": ").append(e.getMessage()).append('\n');}
          }
        }
      }catch(Exception e){report.append("Falha geral: ").append(e.getMessage());errors++;}
      int ok=moved,bad=errors;String detail=report.toString();
      runOnUiThread(()->{
        busy=false;selected.clear();addLog("Organizador "+mode+": "+ok+" movidos; "+bad+" falhas");
        refresh();showReport("Resultado da organização",ok+" arquivo(s) movido(s); "+bad+" falha(s).\n\n"+
          (detail.isEmpty()?"Sem falhas registradas.":detail)+"\nConfira as pastas de destino.");
      });
    });
  }
  void reportFiltered(String title,RowPredicate predicate){
    ArrayList<Row> found=new ArrayList<>();for(Row r:rows)if(predicate.test(r))found.add(r);
    found.sort((a,b)->a.name.compareToIgnoreCase(b.name));
    StringBuilder b=new StringBuilder("Pasta atual (sem recursão) · "+found.size()+" item(ns)\n\n");
    for(Row r:found){if(b.length()>25000){b.append("[Relatório limitado]");break;}
      b.append(r.name).append(" · ").append(sizeString(r.size)).append(" · ").append(dateString(r.modified)).append('\n');}
    showReport(title,b.toString());
  }
  void reportOrganizer(int choice){
    if(inZip||root==null){fail("Abra uma pasta normal");return;}
    ArrayList<Row> files=organizerFiles(false);ArrayList<Row> sorted=new ArrayList<>(files);
    if(choice==8||choice==9||choice==10){
      if(choice==8)sorted.sort((a,b)->Long.compare(b.size,a.size));
      if(choice==9)sorted.sort((a,b)->Long.compare(a.modified<=0?Long.MAX_VALUE:a.modified,b.modified<=0?Long.MAX_VALUE:b.modified));
      if(choice==10)sorted.sort((a,b)->Long.compare(b.modified,a.modified));
      ArrayList<Row> top=new ArrayList<>(sorted.subList(0,Math.min(50,sorted.size())));
      String[] labels={"Maiores","Mais antigos","Mais recentes"};
      reportFromRows(labels[choice-8]+" · até 50",top);return;
    }
    if(choice==11){reportFiltered("Arquivos sem extensão",r->!r.folder&&normalizedExtension(r.name).equals("Sem_extensao"));return;}
    if(choice==12){reportFiltered("Arquivos sem data",r->!r.folder&&r.modified<=0);return;}
    if(choice==13){reportFiltered("Arquivos sem tamanho",r->!r.folder&&r.size<0);return;}
    if(choice==14){reportFiltered("Arquivos vazios",r->!r.folder&&r.size==0);return;}
    if(choice==15){reportFiltered("Ocultos visíveis",r->r.name.startsWith("."));return;}
    int mode=choice==16?1:choice==17?3:choice==18?4:choice==19?2:0;
    TreeMap<String,Long> counts=new TreeMap<>(),bytes=new TreeMap<>();
    for(Row r:files){String group=groupFor(r,mode);counts.put(group,counts.getOrDefault(group,0L)+1);
      bytes.put(group,bytes.getOrDefault(group,0L)+Math.max(0,r.size));}
    StringBuilder report=new StringBuilder("Pasta atual; somente arquivos visíveis.\nTotal: "+files.size()+"\n\n");
    for(String group:counts.keySet())report.append(group).append(": ").append(counts.get(group)).append(" arquivo(s), ").append(sizeString(bytes.get(group))).append('\n');
    showReport("Distribuição de arquivos",report.toString());
  }
  void reportFromRows(String title,ArrayList<Row> items){
    StringBuilder b=new StringBuilder();for(Row r:items)b.append(r.name).append(" · ").append(sizeString(r.size)).append(" · ").append(dateString(r.modified)).append('\n');
    showReport(title,b.length()==0?"Nenhum arquivo encontrado.":b.toString());
  }

  void showOperations(){
    String[] ops={"Criar pasta","Criar arquivo vazio","Renomear selecionado","Copiar selecionados","Mover selecionados","Excluir selecionados","Detalhes selecionados","Compartilhar selecionados","Abrir/editar arquivo de texto","Renomear em lote","Histórico de operações","Duplicar arquivos (na pasta atual)","✂ Recortar seleção","📋 Copiar seleção para área de transferência","📥 Colar na pasta atual","Limpar área de transferência","Tamanho dos selecionados","Copiar nomes selecionados","Comparar 2 arquivos (SHA-256)","Encontrar duplicados na pasta","Relatório da pasta (copiar)","Contar tipos e tamanhos","Copiar caminhos selecionados","Visualizar detalhes de todos os selecionados"};
    new AlertDialog.Builder(this).setTitle("Gerenciar arquivos").setItems(ops,(d,w)->{
      switch(w){case 0:createItem(true);break;case 1:createItem(false);break;
        case 2:renameSelected();break;case 3:if(!selected.isEmpty())chooseFolder(COPY);else fail("Marque um arquivo ou pasta");break;
        case 4:startMove();break;case 5:deleteSelected();break;case 6:detailsSelected();break;
        case 7:shareSelected();break;case 8:editSelected();break;case 9:batchRenameMenu();break;
        case 10:showOperationLog();break;case 11:duplicateSelected();break;
        case 12:saveClipboard(true);break;case 13:saveClipboard(false);break;
        case 14:pasteClipboard();break;
        case 15:clipboardRows.clear();clipboardLabel="";render("Área de transferência limpa");break;
        case 16:selectedSizes();break;case 17:copySelectedNames();break;
        case 18:compareSelected();break;case 19:findDuplicates();break;
        case 20:folderReport();break;case 21:folderSummary();break;
        case 22:copySelectedPaths();break;case 23:detailsMany();break;}
    }).show();
  }
  void showArchiveTools(){
    String[] options={"Compactar sem pasta principal (ZiptoGit)","Compactar com pasta principal Projeto/","Nível de compressão (0 a 9)","Prévia do ZIP aberto","Verificar integridade do ZIP","Auditoria de ZIP para GitHub"};
    new AlertDialog.Builder(this).setTitle("Ferramentas ZIP")
      .setItems(options,(d,w)->{
        switch(w){
          case 0:includeContainer=false;startPack();break;
          case 1:includeContainer=true;startPack();break;
          case 2:chooseCompression();break;
          case 3:previewZip();break;
          case 4:checkZipIntegrity();break;
          case 5:githubZipAudit();break;
        }
      }).setNegativeButton("Cancelar",null).show();
  }
  void chooseCompression(){
    String[] levels={"0 - sem compressão","1 - mais rápido","3 - rápido","6 - equilibrado","9 - menor tamanho"};
    int[] values={0,1,3,6,9};
    new AlertDialog.Builder(this).setTitle("Nível de compressão")
      .setItems(levels,(d,w)->{compressionLevel=values[w];prefs.edit().putInt("compressionLevel",compressionLevel).apply();Toast.makeText(this,"Compressão: "+compressionLevel,Toast.LENGTH_SHORT).show();})
      .setNegativeButton("Cancelar",null).show();
  }
  void createItem(boolean directory){
    if(root==null||inZip){fail("Abra uma pasta normal");return;}
    prompt(directory?"Nome da pasta":"Nome do arquivo",directory?"NovaPasta":"novo.txt",value->{
      if(!validFilename(value))return;
      runTask("Criando...",()->{
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(root,docId);
        Uri made=DocumentsContract.createDocument(getContentResolver(),parent,directory?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain",value);
        if(made==null)throw new IOException("Provedor recusou criação");
      });
    });
  }
  interface TextReceiver{void accept(String value);}
  void prompt(String title,String initial,TextReceiver action){
    EditText field=new EditText(this);field.setSingleLine(true);field.setText(initial);field.setSelectAllOnFocus(true);
    LinearLayout wrap=new LinearLayout(this);wrap.setPadding(24,8,24,4);wrap.addView(field);
    new AlertDialog.Builder(this).setTitle(title).setView(wrap).setNegativeButton("Cancelar",null)
      .setPositiveButton("OK",(d,w)->action.accept(field.getText().toString().trim())).show();
  }
  boolean validFilename(String name){
    if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")){fail("Nome inválido");return false;}return true;
  }
  void runTask(String message,Task task){
    if(busy)return;busy=true;render(message);
    worker.execute(()->{String error=null;try{task.run();}catch(Exception e){error=e.getMessage();}
      String result=error;runOnUiThread(()->{busy=false;selected.clear();refresh();addLog(result==null?message+" concluído":message+" ERRO: "+result);Toast.makeText(this,result==null?"Concluído":("Erro: "+result),Toast.LENGTH_LONG).show();});
    });
  }
  interface Task{void run()throws Exception;}
  void renameSelected(){
    ArrayList<Row> t=selectedFiles();if(t.size()!=1){fail("Marque apenas um item");return;}
    Row r=t.get(0);prompt("Renomear",r.name,name->{if(!validFilename(name))return;
      runTask("Renomeando...",()->{
        Uri item=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
        if(DocumentsContract.renameDocument(getContentResolver(),item,name)==null)throw new IOException("Renomeação não suportada");
      });
    });
  }
  void deleteSelected(){
    ArrayList<Row> t=selectedFiles();if(t.isEmpty()){fail("Marque os itens");return;}
    new AlertDialog.Builder(this).setTitle("Excluir definitivamente?").setMessage("Excluir "+t.size()+" item(ns), inclusive conteúdo de pastas? Pode ser irreversível.")
      .setNegativeButton("Cancelar",null).setPositiveButton("EXCLUIR",(d,w)->runTask("Excluindo...",()->{
        for(Row r:t){Uri item=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
          if(!DocumentsContract.deleteDocument(getContentResolver(),item))throw new IOException("Falhou: "+r.name);}
      })).show();
  }
  void copySelected(Uri dest){
    ArrayList<Row> t=selectedFiles();if(t.isEmpty())return;
    Uri parent=DocumentsContract.buildDocumentUriUsingTree(dest,DocumentsContract.getTreeDocumentId(dest));
    runTask("Copiando...",()->{
      for(Row r:t){Uri item=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
        Uri result=DocumentsContract.copyDocument(getContentResolver(),item,parent);
        if(result==null)throw new IOException("Cópia não suportada para: "+r.name);
      }
    });
  }
  void selectedSizes(){
    ArrayList<Row> t=selectedFiles();long bytes=0;int unknown=0,dirs=0;
    for(Row r:t){if(r.folder)dirs++;if(r.size<0)unknown++;else bytes+=r.size;}
    new AlertDialog.Builder(this).setTitle("Resumo da seleção")
      .setMessage("Itens: "+t.size()+"\nPastas: "+dirs+"\nTotal de tamanhos informados: "+sizeString(bytes)+"\nTamanho desconhecido: "+unknown+"\nNão calcula recursivamente pastas.")
      .setPositiveButton("OK",null).show();
  }
  void copySelectedNames(){
    ArrayList<Row> t=selectedFiles();if(t.isEmpty()){fail("Selecione pelo menos um item");return;}
    ArrayList<String> names=new ArrayList<>();for(Row r:t)names.add(r.name);
    android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
    clip.setPrimaryClip(ClipData.newPlainText("nomes",String.join("\n",names)));
    Toast.makeText(this,"Nomes copiados",Toast.LENGTH_SHORT).show();
  }
  void detailsSelected(){
    ArrayList<Row> t=selectedFiles();if(t.size()!=1){fail("Marque um item");return;}
    Row r=t.get(0);
    new AlertDialog.Builder(this).setTitle(r.name).setMessage("Tipo: "+r.mime+"\nTamanho: "+sizeString(r.size)+"\nModificado: "+dateString(r.modified)+"\nID: "+r.id)
      .setPositiveButton("OK",null).show();
  }
  void shareSelected(){
    ArrayList<Row> t=selectedFiles();if(t.isEmpty()){fail("Marque arquivos");return;}
    ArrayList<Uri> links=new ArrayList<>();for(Row r:t)if(!r.folder)links.add(DocumentsContract.buildDocumentUriUsingTree(root,r.id));
    if(links.isEmpty()){fail("Selecione arquivos normais");return;}
    Intent intent=new Intent(links.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
    intent.setType("*/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if(links.size()==1)intent.putExtra(Intent.EXTRA_STREAM,links.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,links);
    startActivity(Intent.createChooser(intent,"Compartilhar"));
  }
  void editSelected(){ArrayList<Row> t=selectedFiles();if(t.size()!=1||t.get(0).folder){fail("Marque um arquivo de texto");return;}editText(t.get(0));}
  void fileActions(Row r){
    String[] choices={"Abrir como texto / editar","Detalhes","Compartilhar","Visualizar imagem","Visualizar PDF","Prévia de texto","Visualizar HEX","Propriedades da imagem","SHA-256 / CRC32","Abrir em outro aplicativo"};
    new AlertDialog.Builder(this).setTitle(r.name).setItems(choices,(d,which)->{
      if(which==0)editText(r);else if(which==1){selected.clear();selected.add(r.id);detailsSelected();}
      else if(which==2){selected.clear();selected.add(r.id);shareSelected();}
      else if(which==3)viewImage(r);
      else if(which==4)viewPdf(r);
      else if(which==5)textPreview(r);
      else if(which==6)hexPreview(r);
      else if(which==7)imageInfo(r);
      else if(which==8)hashFile(r);
      else openExternally(r);
    }).show();
  }
  void viewImage(Row r){
    Uri uri=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
    worker.execute(()->{
      Bitmap bmp=null;String error=null;
      try(InputStream in=getContentResolver().openInputStream(uri)){
        BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=2;bmp=BitmapFactory.decodeStream(in,null,opt);
        if(bmp==null)throw new IOException("Formato de imagem indisponível");
      }catch(Exception e){error=e.getMessage();}
      Bitmap image=bmp;String problem=error;
      runOnUiThread(()->{if(problem!=null){fail(problem);return;}
        ImageView v=new ImageView(this);v.setImageBitmap(image);v.setAdjustViewBounds(true);
        new AlertDialog.Builder(this).setTitle(r.name).setView(v).setPositiveButton("Fechar",null).show();});
    });
  }
  void editText(Row r){
    Uri uri=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
    worker.execute(()->{
      String content=null,error=null;
      try(InputStream in=getContentResolver().openInputStream(uri)){
        ByteArrayOutputStream bout=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(bout.size()+n>1024*1024)throw new IOException("Editor limitado a 1 MB");bout.write(buf,0,n);}
        content=bout.toString("UTF-8");
      }catch(Exception e){error=e.getMessage();}
      String finalContent=content,problem=error;
      runOnUiThread(()->{if(problem!=null){fail(problem);return;}
        EditText editor=new EditText(this);editor.setGravity(Gravity.TOP);editor.setTextSize(13);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setText(finalContent);
        LinearLayout editorBox=new LinearLayout(this);editorBox.setOrientation(1);editorBox.setPadding(12,8,12,4);
        TextView editorStats=new TextView(this);editorStats.setTextSize(12);editorBox.addView(editorStats);
        Runnable updateStats=()->editorStats.setText("Linhas: "+lineCount(editor.getText().toString())+"  •  Palavras: "+wordCount(editor.getText().toString())+"  •  Caracteres: "+editor.length());
        editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence v,int a,int n,int x){} public void onTextChanged(CharSequence v,int a,int before,int count){updateStats.run();}public void afterTextChanged(Editable v){}});
        updateStats.run();
        LinearLayout tb=new LinearLayout(this);
        Button find=button("Buscar"),replace=button("Trocar"),go=button("Linha"),size=button("A±");
        for(Button b:new Button[]{find,replace,go,size})tb.addView(b,new LinearLayout.LayoutParams(0,-2,1));editorBox.addView(tb);
        find.setOnClickListener(v->prompt("Buscar no texto","",word->{int at=editor.getText().toString().toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT),Math.max(0,editor.getSelectionStart()));if(at<0)at=editor.getText().toString().toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT));if(word.length()>0&&at>=0){editor.setSelection(at,at+word.length());Toast.makeText(this,"Encontrado",Toast.LENGTH_SHORT).show();}else fail("Texto não encontrado");}));
        replace.setOnClickListener(v->prompt("Encontrar e substituir — encontrar","",needle->prompt("Substituir por","",replacement->{if(needle.isEmpty())return;String old=editor.getText().toString();String newer=old.replace(needle,replacement);editor.setText(newer);Toast.makeText(this,"Substituições: "+((old.length()-old.replace(needle,"").length())/needle.length()),Toast.LENGTH_SHORT).show();})));
        go.setOnClickListener(v->prompt("Ir para linha","1",number->{try{int n=Integer.parseInt(number);String txt=editor.getText().toString();int pos=0;for(int i=1;i<n&&pos<txt.length();i++){int next=txt.indexOf('\n',pos);if(next<0){pos=txt.length();break;}pos=next+1;}editor.setSelection(pos);}catch(Exception e){fail("Linha inválida");}}));
        size.setOnClickListener(v->{float sz=editor.getTextSize()/getResources().getDisplayMetrics().scaledDensity;editor.setTextSize(sz>=20?12:sz+2);});
        ScrollView sc=new ScrollView(this);sc.addView(editor);editorBox.addView(sc,new LinearLayout.LayoutParams(-1,(int)(350*getResources().getDisplayMetrics().density)));
        new AlertDialog.Builder(this).setTitle("Editar "+r.name).setView(editorBox)
          .setNegativeButton("Fechar",null).setNeutralButton("Salvar cópia",(d,w)->{
            String newContent=editor.getText().toString();
            prompt("Nome da cópia",r.name+".copia.txt",name->{if(!validFilename(name))return;
              runTask("Salvando cópia...",()->{
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(root,docId);
                Uri created=DocumentsContract.createDocument(getContentResolver(),parent,"text/plain",name);
                if(created==null)throw new IOException("Não foi possível criar arquivo");
                try(OutputStream o=getContentResolver().openOutputStream(created,"w")){o.write(newContent.getBytes("UTF-8"));}
              });
            });
          }).setPositiveButton("Salvar",(d,w)->{
            String val=editor.getText().toString();
            new AlertDialog.Builder(this).setTitle("Sobrescrever arquivo?").setMessage("Alterar "+r.name+"? Essa operação muda a data de modificação.")
              .setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(dialog,which)->runTask("Salvando...",()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                  if(out==null)throw new IOException("Sem permissão de escrita");out.write(val.getBytes("UTF-8"));
                }
              })).show();
          }).show();
      });
    });
  }

  // Ferramentas de visualização 2.5: nenhuma depende de bibliotecas remotas.
  static int lineCount(String t){if(t.isEmpty())return 0;int n=1;for(int i=0;i<t.length();i++)if(t.charAt(i)=='\n')n++;return n;}
  static int wordCount(String t){String a=t.trim();return a.isEmpty()?0:a.split("\\s+").length;}
  Uri uriFor(Row r){return DocumentsContract.buildDocumentUriUsingTree(root,r.id);}
  void copyText(String label,String value){android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,"Copiado: "+label,Toast.LENGTH_SHORT).show();}
  void showReport(String title,String report){ScrollView sc=new ScrollView(this);TextView t=new TextView(this);t.setText(report);t.setTextIsSelectable(true);t.setPadding(20,16,20,16);t.setTypeface(android.graphics.Typeface.MONOSPACE);sc.addView(t);new AlertDialog.Builder(this).setTitle(title).setView(sc).setNeutralButton("Copiar",(d,w)->copyText(title,report)).setPositiveButton("Fechar",null).show();}
  void backgroundReport(String title,java.util.concurrent.Callable<String> job){if(busy){fail("Outra operação em andamento");return;}busy=true;status.setText("Analisando: "+title);worker.execute(()->{String output;try{output=job.call();}catch(Exception e){output="Erro: "+e.getMessage();}String result=output;runOnUiThread(()->{busy=false;render(title);showReport(title,result);});});}
  void openExternally(Row r){try{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uriFor(r),r.mime==null?"*/*":r.mime);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Abrir com"));}catch(Exception e){fail("Não há aplicativo compatível: "+e.getMessage());}}
  void textPreview(Row r){backgroundReport("Prévia de "+r.name,()->{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uriFor(r))){byte[] buf=new byte[4096];int n;while((n=in.read(buf))>0){if(out.size()+n>128*1024)break;out.write(buf,0,n);}}String content=out.toString("UTF-8");return content+(r.size>128*1024?"\n\n[Prévia limitada a 128 KB]":"");});}
  void hexPreview(Row r){backgroundReport("HEX · "+r.name,()->{byte[] data=new byte[1024];int n;try(InputStream in=getContentResolver().openInputStream(uriFor(r))){n=in.read(data);}if(n<0)n=0;StringBuilder b=new StringBuilder("Primeiros "+n+" bytes (limite 1024)\n\n");for(int i=0;i<n;i+=16){b.append(String.format(Locale.US,"%06X  ",i));StringBuilder a=new StringBuilder();for(int j=i;j<i+16;j++){if(j<n){int v=data[j]&255;b.append(String.format(Locale.US,"%02X ",v));a.append(v>=32&&v<=126?(char)v:'.');}else b.append("   ");}b.append("  ").append(a).append('\n');}return b.toString();});}
  void imageInfo(Row r){backgroundReport("Imagem · "+r.name,()->{BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uriFor(r))){BitmapFactory.decodeStream(in,null,opt);}if(opt.outWidth<=0)throw new IOException("Não é imagem suportada");return "Nome: "+r.name+"\nDimensões: "+opt.outWidth+" × "+opt.outHeight+" pixels\nFormato: "+opt.outMimeType+"\nTamanho: "+sizeString(r.size)+"\nModificação: "+dateString(r.modified);});}
  void viewPdf(Row r){if(android.os.Build.VERSION.SDK_INT<21){fail("PDF indisponível");return;}if(busy)return;busy=true;status.setText("Abrindo PDF...");worker.execute(()->{Bitmap page=null;String problem=null;int count=0;try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uriFor(r),"r")){if(fd==null)throw new IOException("PDF indisponível");try(PdfRenderer pdf=new PdfRenderer(fd)){count=pdf.getPageCount();if(count<1)throw new IOException("PDF vazio");try(PdfRenderer.Page pg=pdf.openPage(0)){float ratio=Math.min(1.7f,1200f/Math.max(1,pg.getWidth()));int w=Math.max(1,(int)(pg.getWidth()*ratio)),h=Math.max(1,(int)(pg.getHeight()*ratio));if((long)w*h>5000000)throw new IOException("Página excessivamente grande");page=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);page.eraseColor(Color.WHITE);pg.render(page,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}}}catch(Exception e){problem=e.getMessage();}Bitmap bmp=page;String err=problem;int total=count;runOnUiThread(()->{busy=false;if(err!=null){fail("PDF: "+err);return;}ImageView img=new ImageView(this);img.setAdjustViewBounds(true);img.setImageBitmap(bmp);ScrollView scroll=new ScrollView(this);scroll.addView(img);new AlertDialog.Builder(this).setTitle("PDF · primeira página de "+total).setView(scroll).setPositiveButton("Fechar",null).show();});});}
  String digest(Row r,String algorithm)throws Exception{MessageDigest md=MessageDigest.getInstance(algorithm);long n=0;try(InputStream in=getContentResolver().openInputStream(uriFor(r))){if(in==null)throw new IOException("Sem acesso");byte[] b=new byte[65536];int k;while((k=in.read(b))!=-1){n+=k;if(n>1024L*1024*1024)throw new IOException("Limite: 1 GB");md.update(b,0,k);}}StringBuilder hex=new StringBuilder();for(byte a:md.digest())hex.append(String.format(Locale.US,"%02x",a&255));return hex.toString();}
  void hashFile(Row r){backgroundReport("Integridade · "+r.name,()->{String sha=digest(r,"SHA-256");java.util.zip.CRC32 crc=new java.util.zip.CRC32();try(InputStream in=getContentResolver().openInputStream(uriFor(r))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)crc.update(b,0,n);}return "Arquivo: "+r.name+"\nSHA-256: "+sha+"\nCRC32: "+Long.toHexString(crc.getValue()).toUpperCase(Locale.ROOT);});}
  void compareSelected(){ArrayList<Row> a=selectedFiles();if(a.size()!=2||a.get(0).folder||a.get(1).folder){fail("Marque exatamente 2 arquivos");return;}backgroundReport("Comparação SHA-256",()->{String h1=digest(a.get(0),"SHA-256"),h2=digest(a.get(1),"SHA-256");return a.get(0).name+"\n"+h1+"\n\n"+a.get(1).name+"\n"+h2+"\n\n"+(h1.equals(h2)?"Conteúdo idêntico (SHA-256)":"Conteúdo diferente");});}
  void findDuplicates(){if(inZip||root==null){fail("Abra uma pasta normal");return;}ArrayList<Row> files=new ArrayList<>();for(Row r:rows)if(!r.folder&&r.size>=0&&r.size<=32*1024*1024)files.add(r);if(files.size()>120){fail("Limite: 120 arquivos visíveis");return;}backgroundReport("Duplicados nesta pasta",()->{Map<Long,ArrayList<Row>> sizes=new HashMap<>();for(Row r:files)sizes.computeIfAbsent(r.size,k->new ArrayList<>()).add(r);Map<String,ArrayList<String>> hashes=new LinkedHashMap<>();for(ArrayList<Row> group:sizes.values())if(group.size()>1)for(Row r:group){String sha=digest(r,"SHA-256");hashes.computeIfAbsent(sha,k->new ArrayList<>()).add(r.name);}StringBuilder out=new StringBuilder("Busca não recursiva; até 120 arquivos de no máximo 32 MB cada.\n\n");int groups=0;for(Map.Entry<String,ArrayList<String>> e:hashes.entrySet())if(e.getValue().size()>1){groups++;out.append("Grupo ").append(groups).append(":\n").append(String.join("\n",e.getValue())).append("\n\n");}if(groups==0)out.append("Nenhum duplicado encontrado nos arquivos analisados.");return out.toString();});}
  void folderReport(){if(inZip){fail("Use uma pasta normal");return;}StringBuilder b=new StringBuilder("Pasta: "+String.join("/",pathNames)+"\nItens visíveis: "+rows.size()+"\n\n");for(Row r:rows)b.append(r.folder?"[DIR] ":"[ARQ] ").append(r.name).append(" | ").append(sizeString(r.size)).append(" | ").append(dateString(r.modified)).append("\n");showReport("Inventário da pasta",b.toString());}
  void folderSummary(){int folders=0,files=0,hiddenCount=0;long bytes=0;Map<String,Integer> ext=new TreeMap<>();for(Row r:rows){if(r.folder)folders++;else{files++;if(r.size>0)bytes+=r.size;int dot=r.name.lastIndexOf('.');String e=dot<0?"(sem extensão)":r.name.substring(dot).toLowerCase(Locale.ROOT);ext.put(e,ext.getOrDefault(e,0)+1);}if(r.name.startsWith("."))hiddenCount++;}StringBuilder b=new StringBuilder("Pastas: "+folders+"\nArquivos: "+files+"\nOcultos visíveis: "+hiddenCount+"\nSoma dos tamanhos conhecidos: "+sizeString(bytes)+"\n\nPor extensão:\n");for(Map.Entry<String,Integer> e:ext.entrySet())b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');showReport("Resumo da pasta",b.toString());}
  void copySelectedPaths(){ArrayList<Row> t=selectedFiles();if(t.isEmpty()){fail("Marque arquivos ou pastas");return;}StringBuilder b=new StringBuilder();for(Row r:t)b.append(String.join("/",pathNames)).append('/').append(r.name).append('\n');copyText("caminhos relativos",b.toString());}
  void detailsMany(){ArrayList<Row> t=selectedFiles();if(t.isEmpty()){fail("Marque arquivos ou pastas");return;}StringBuilder b=new StringBuilder();for(Row r:t)b.append(r.name).append("\nTipo: ").append(r.mime).append("\nTamanho: ").append(sizeString(r.size)).append("\nModificado: ").append(dateString(r.modified)).append("\n\n");showReport("Detalhes selecionados",b.toString());}

  void searchDialog(){prompt("Buscar nesta pasta (nome)",search,value->{search=value;refresh();});}
  void searchMenu(){
    String[] choices={"Buscar por nome (pasta atual)","Pesquisar recursivamente (subpastas)","Filtrar por extensão",
      "Filtrar só arquivos","Filtrar só pastas","Filtrar por tamanho mínimo (KB)","Filtrar por tamanho máximo (KB)",
      "Filtrar modificados nos últimos N dias","Limpar todos os filtros","Ver filtros ativos"};
    new AlertDialog.Builder(this).setTitle("Pesquisa e filtros").setItems(choices,(d,w)->{
      switch(w){
        case 0:searchDialog();break;
        case 1:prompt("Nome a procurar nas subpastas",search,v->recursiveSearch(v));break;
        case 2:prompt("Extensão (ex: .pdf)",extensionFilter,v->{extensionFilter=v.trim();refresh();});break;
        case 3:onlyFiles=true;onlyFolders=false;refresh();break;
        case 4:onlyFolders=true;onlyFiles=false;refresh();break;
        case 5:prompt("Tamanho mínimo em KB","100",v->{try{minFilterSize=Long.parseLong(v)*1024;refresh();}catch(Exception e){fail("Número inválido");}});break;
        case 6:prompt("Tamanho máximo em KB","1024",v->{try{maxFilterSize=Long.parseLong(v)*1024;refresh();}catch(Exception e){fail("Número inválido");}});break;
        case 7:prompt("Modificados nos últimos quantos dias?","7",v->{try{afterFilter=System.currentTimeMillis()-Long.parseLong(v)*86400000L;refresh();}catch(Exception e){fail("Número inválido");}});break;
        case 8:search="";extensionFilter="";minFilterSize=-1;maxFilterSize=-1;afterFilter=0;beforeFilter=0;onlyFiles=false;onlyFolders=false;refresh();break;
        case 9:new AlertDialog.Builder(this).setTitle("Filtros ativos").setMessage("Nome: "+search+"\nExtensão: "+extensionFilter+"\nSomente arquivos: "+onlyFiles+"\nSomente pastas: "+onlyFolders+"\nTamanho mín/máx (bytes): "+minFilterSize+"/"+maxFilterSize+"\nDesde: "+dateString(afterFilter)).setPositiveButton("OK",null).show();break;
      }
    }).show();
  }
  void recursiveSearch(String needle){
    if(root==null||inZip||docId==null){fail("Abra uma pasta antes");return;}
    final Uri tree=root;final String baseId=docId;final String term=needle.toLowerCase(Locale.ROOT);
    if(term.isEmpty()){fail("Informe um termo de busca");return;}
    busy=true;render("Pesquisando subpastas...");
    worker.execute(()->{
      ArrayList<String> found=new ArrayList<>();String error=null;
      try{
        ArrayDeque<String[]> queue=new ArrayDeque<>();queue.add(new String[]{baseId,""});
        HashSet<String> seen=new HashSet<>();int directories=0;
        while(!queue.isEmpty()&&found.size()<MAX_SEARCH_RESULTS){
          String[] at=queue.removeFirst();if(!seen.add(at[0]))continue;
          if(++directories>3000)throw new IOException("Busca limitada a 3000 pastas");
          Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,at[0]);
          String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
          try(Cursor c=getContentResolver().query(children,cols,null,null,null)){
            if(c==null)continue;
            while(c.moveToNext()){
              String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
              if(id==null||name==null||(!hidden&&name.startsWith(".")))continue;
              boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
              String filePath=at[1]+name+(dir?"/":"");
              if(name.toLowerCase(Locale.ROOT).contains(term))found.add(filePath);
              if(dir&&filePath.split("/").length<=32)queue.add(new String[]{id,filePath});
              if(found.size()>=MAX_SEARCH_RESULTS)break;
            }
          }
        }
      }catch(Exception e){error=e.getMessage();}
      final String problem=error;runOnUiThread(()->{busy=false;refresh();
        StringBuilder b=new StringBuilder();for(String item:found){if(b.length()>22000)break;b.append(item).append('\n');}
        new AlertDialog.Builder(this).setTitle("Busca: "+found.size()+" resultado(s)")
          .setMessage((problem==null?"":"Aviso: "+problem+"\n")+"Resultados (caminhos relativos):\n\n"+b)
          .setPositiveButton("Fechar",null).show();
      });
    });
  }
  void showOperationLog(){
    new AlertDialog.Builder(this).setTitle("Histórico de operações")
      .setItems(operationLog.toArray(new String[0]),(d,w)->{})
      .setNeutralButton("Limpar histórico",(d,w)->new AlertDialog.Builder(this).setTitle("Apagar registro de operações?")
        .setNegativeButton("Cancelar",null).setPositiveButton("Apagar",(a,b)->{operationLog.clear();prefs.edit().remove("oplog").apply();}).show())
      .setPositiveButton("Fechar",null).show();
  }
  void duplicateSelected(){
    ArrayList<Row> t=selectedFiles();if(t.isEmpty()||root==null||inZip){fail("Selecione itens da pasta atual");return;}
    final Uri target=DocumentsContract.buildDocumentUriUsingTree(root,docId);
    new AlertDialog.Builder(this).setTitle("Duplicar nesta pasta")
      .setMessage("Criar cópias de "+t.size()+" item(ns)? O provedor define o nome das cópias.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Duplicar",(d,w)->runTask("Duplicando",()->{
        for(Row r:t){Uri doc=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
          if(DocumentsContract.copyDocument(getContentResolver(),doc,target)==null)throw new IOException("Cópia não suportada: "+r.name);}
      })).show();
  }
  void batchRenameMenu(){
    ArrayList<Row> t=selectedFiles();if(inZip||t.isEmpty()){fail("Marque arquivos na pasta atual");return;}
    String[] choices={"Adicionar prefixo","Adicionar sufixo (antes da extensão)","Substituir texto", "Numerar automaticamente", "MAIÚSCULAS", "minúsculas", "Espaços → sublinhados", "Remover espaços duplicados"};
    new AlertDialog.Builder(this).setTitle("Renomear "+t.size()+" item(ns)").setItems(choices,(d,w)->{
      if(w==0)prompt("Prefixo", "novo_",value->previewBatchRename(t,0,value,""));
      if(w==1)prompt("Sufixo", "_novo",value->previewBatchRename(t,1,value,""));
      if(w==2)prompt("Texto a substituir", "antigo",a->prompt("Substituir por", "novo",b->previewBatchRename(t,2,a,b)));
      if(w==3)prompt("Prefixo para numeração", "arquivo_",value->previewBatchRename(t,3,value,""));
      if(w>=4)previewBatchRename(t,w,"","");
    }).show();
  }
  String renamed(String name,int op,String a,String b,int n){
    int p=name.lastIndexOf('.');String base=p>0?name.substring(0,p):name,ext=p>0?name.substring(p):"";
    switch(op){
      case 0:return a+name;
      case 1:return base+a+ext;
      case 2:return name.replace(a,b);
      case 3:return a+String.format(Locale.ROOT,"%03d",n)+ext;
      case 4:return name.toUpperCase(Locale.ROOT);
      case 5:return name.toLowerCase(Locale.ROOT);
      case 6:return name.replace(' ','_');
      case 7:return name.trim().replaceAll(" +"," ");
      default:return name;
    }
  }
  void previewBatchRename(ArrayList<Row> targets,int op,String a,String b){
    if(op==2&&a.isEmpty()){fail("Texto buscado não pode estar vazio");return;}
    LinkedHashMap<Row,String> names=new LinkedHashMap<>();HashSet<String> changed=new HashSet<>();StringBuilder preview=new StringBuilder();int n=0;
    for(Row r:targets){String name=renamed(r.name,op,a,b,++n);
      if(!validFilename(name))return;
      if(!changed.add(name.toLowerCase(Locale.ROOT))){fail("Nomes duplicados no lote: "+name);return;}
      names.put(r,name);if(preview.length()<6000)preview.append(r.name).append(" → ").append(name).append('\n');
    }
    new AlertDialog.Builder(this).setTitle("Confirmar renomeação de "+targets.size()+" itens")
      .setMessage("Prévia (sem desfazer automático):\n\n"+preview)
      .setNegativeButton("Cancelar",null).setPositiveButton("Renomear",(d,w)->runTask("Renomeando lote",()->{
        for(Map.Entry<Row,String> item:names.entrySet()){
          Row r=item.getKey();if(r.name.equals(item.getValue()))continue;
          Uri uri=DocumentsContract.buildDocumentUriUsingTree(root,r.id);
          if(DocumentsContract.renameDocument(getContentResolver(),uri,item.getValue())==null)
            throw new IOException("Falhou: "+r.name+" (itens anteriores podem ter sido renomeados)");
        }
      })).show();
  }
  void sortDialog(){String[] opts={"Nome","Tamanho","Data de modificação","Tipo","Inverter ordem"};
    new AlertDialog.Builder(this).setTitle("Ordenação").setItems(opts,(d,w)->{
      if(w==4)reverse=!reverse;else sortMode=new String[]{"name","size","date","type"}[w];
      prefs.edit().putString("sort",sortMode).putBoolean("reverse",reverse).apply();refresh();
    }).show();
  }
  void selectDialog(){String[] opts={"Selecionar todos","Desmarcar todos","Inverter seleção","Selecionar por extensão","Selecionar só arquivos","Selecionar só pastas","Selecionar por nome","Selecionar por tamanho","Selecionar por data"};
    new AlertDialog.Builder(this).setTitle("Seleção").setItems(opts,(d,w)->{
      if(w==0){for(Row r:rows)selected.add(r.id);render("Tudo selecionado");}
      if(w==1){selected.clear();render("Seleção limpa");}
      if(w==2){for(Row r:rows)if(!selected.add(r.id))selected.remove(r.id);render("Seleção invertida");}
      if(w==3)prompt("Extensão (ex.: .txt)",".zip",value->{for(Row r:rows)if(r.name.toLowerCase(Locale.ROOT).endsWith(value.toLowerCase(Locale.ROOT)))selected.add(r.id);render("Selecionado por extensão");});
      if(w==4){for(Row r:rows)if(!r.folder)selected.add(r.id);render("Arquivos selecionados");}
      if(w==5){for(Row r:rows)if(r.folder)selected.add(r.id);render("Pastas selecionadas");}
      if(w==6)prompt("Trecho do nome","foto",value->{for(Row r:rows)if(r.name.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)))selected.add(r.id);render("Seleção por nome");});
      if(w==7)prompt("Maior que (KB)","1024",value->{try{long min=Long.parseLong(value)*1024;for(Row r:rows)if(r.size>=min)selected.add(r.id);render("Seleção por tamanho");}catch(Exception e){fail("Valor inválido");}});
      if(w==8)prompt("Modificados nos últimos N dias","7",value->{try{long min=System.currentTimeMillis()-Long.parseLong(value)*86400000L;for(Row r:rows)if(r.modified>min)selected.add(r.id);render("Seleção por data");}catch(Exception e){fail("Valor inválido");}});
    }).show();
  }
  void showLocations(){
    if(root==null){chooseFolder(FOLDER);return;}
    String now=root.toString();
    String[] opts={"★ Favoritar pasta principal","Abrir pasta favorita","Voltar à pasta principal","Selecionar outra pasta","Histórico recente","Favoritar pasta atual","Copiar caminho atual","Mostrar autorizações de pastas","Aba A","Aba B","Limpar favoritos","Recentes da sessão e acessos"};
    new AlertDialog.Builder(this).setTitle("Locais e favoritos").setItems(opts,(d,w)->{
      if(w==0){favorites.add(now);prefs.edit().putString("favorites",new org.json.JSONArray(favorites).toString()).apply();Toast.makeText(this,"Favorita salva",Toast.LENGTH_SHORT).show();}
      if(w==1){String[] urls=favorites.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Pastas favoritas").setItems(urls,(a,b)->{
        root=Uri.parse(urls[b]);docId=DocumentsContract.getTreeDocumentId(root);pathIds.clear();pathNames.clear();saveLastFolder();refresh();
      }).show();}
      if(w==2){docId=DocumentsContract.getTreeDocumentId(root);pathIds.clear();pathNames.clear();saveLastFolder();refresh();}
      if(w==3)chooseFolder(FOLDER);
      if(w==4)new AlertDialog.Builder(this).setTitle("Histórico da sessão").setItems(history.toArray(new String[0]),(a,b)->Toast.makeText(this,history.get(b),Toast.LENGTH_LONG).show()).show();
      if(w==5){favorites.add(now);prefs.edit().putString("favorites",new org.json.JSONArray(favorites).toString()).apply();Toast.makeText(this,"Pasta base favoritada (subpasta atual lembrada automaticamente)",Toast.LENGTH_LONG).show();}
      if(w==6){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("caminho",String.join("/",pathNames)));Toast.makeText(this,"Caminho copiado",Toast.LENGTH_SHORT).show();}
      if(w==7){StringBuilder b=new StringBuilder();for(android.content.UriPermission perm:getContentResolver().getPersistedUriPermissions())b.append(perm.getUri()).append('\n');new AlertDialog.Builder(this).setTitle("Autorizações guardadas").setMessage(b.length()==0?"Nenhuma":b.toString()).setPositiveButton("Fechar",null).show();}
      if(w==8)switchTab(0);
      if(w==9)switchTab(1);
      if(w==10)new AlertDialog.Builder(this).setTitle("Limpar favoritos?")
        .setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(a,b)->{favorites.clear();prefs.edit().remove("favorites").apply();}).show();
      if(w==11)new AlertDialog.Builder(this).setTitle("Locais recentes")
        .setItems(recentPaths.toArray(new String[0]),(a,b)->{})
        .setNeutralButton("Limpar",(a,b)->{recentPaths.clear();prefs.edit().remove("recentpaths").apply();})
        .setPositiveButton("Fechar",null).show();
    }).show();
  }

}
