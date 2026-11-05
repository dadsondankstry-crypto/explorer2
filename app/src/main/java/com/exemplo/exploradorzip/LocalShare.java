package com.exemplo.exploradorzip;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.ContentResolver;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Servidor temporário de download HTTP na LAN. Sem upload, sem listagem geral e sem acesso por caminho. */
final class LocalShare {
  static final class Item {
    final String name; final Uri uri; final long size;
    Item(String n,Uri u,long s){name=n;uri=u;size=s;}
  }
  private final MainActivity activity;
  private final ArrayList<Item> published=new ArrayList<>();
  private final ExecutorService threads=Executors.newFixedThreadPool(3);
  private ServerSocket server;
  private volatile boolean active;
  private String token="", address="";
  private Thread acceptThread;
  LocalShare(MainActivity a){activity=a;}
  synchronized boolean isRunning(){return active;}
  synchronized String getLink(){return address;}
  synchronized void start(Uri tree,List<MainActivity.Row> rows)throws Exception{
    if(active)throw new IllegalStateException("Servidor já iniciado");
    String host=localIpv4();
    if(host==null)throw new IOException("Conecte seu celular a uma rede Wi-Fi local com IPv4");
    published.clear();
    for(MainActivity.Row r:rows){
      if(r.folder)continue;
      Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,r.id);
      published.add(new Item(r.name,doc,r.size));
    }
    if(published.isEmpty())throw new IOException("Nenhum arquivo selecionado");
    byte[] secret=new byte[24];new SecureRandom().nextBytes(secret);
    StringBuilder hex=new StringBuilder();for(byte b:secret)hex.append(String.format(Locale.ROOT,"%02x",b&255));
    token=hex.toString();
    server=new ServerSocket();server.setReuseAddress(true);
    server.bind(new InetSocketAddress("0.0.0.0",0),8);
    active=true;
    address="http://"+host+":"+server.getLocalPort()+"/?token="+token;
    acceptThread=new Thread(()->{
      while(active){
        try{Socket socket=server.accept();threads.execute(()->serve(socket));}
        catch(Exception e){if(active)android.util.Log.w("ExploradorZIP","Compartilhamento encerrado",e);}
      }
    },"local-wifi-accept");
    acceptThread.setDaemon(true);acceptThread.start();
  }
  synchronized void stop(){
    active=false;try{if(server!=null)server.close();}catch(Exception ignored){}
    server=null;published.clear();token="";address="";
  }
  private String localIpv4(){
    try{
      Enumeration<NetworkInterface> it=NetworkInterface.getNetworkInterfaces();
      ArrayList<String> candidates=new ArrayList<>();
      while(it.hasMoreElements()){
        NetworkInterface nic=it.nextElement();
        if(!nic.isUp()||nic.isLoopback()||nic.isVirtual())continue;
        String n=nic.getName().toLowerCase(Locale.ROOT);
        Enumeration<InetAddress> addr=nic.getInetAddresses();
        while(addr.hasMoreElements()){
          InetAddress ip=addr.nextElement();
          if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&ip.isSiteLocalAddress()){
            if(n.startsWith("wlan")||n.startsWith("wifi"))return ip.getHostAddress();
            candidates.add(ip.getHostAddress());
          }
        }
      }
      return candidates.isEmpty()?null:candidates.get(0);
    }catch(Exception e){return null;}
  }
  private void serve(Socket socket){
    try(Socket s=socket){
      s.setSoTimeout(7000);
      InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
      ByteArrayOutputStream first=new ByteArrayOutputStream();int c;
      while((c=in.read())!=-1&&c!='\n'&&first.size()<4096){if(c!='\r')first.write(c);}
      if(first.size()>=4096){sendError(out,414,"Requisição muito longa");return;}
      String line=first.toString("US-ASCII");
      if(!line.startsWith("GET ")){sendError(out,405,"Apenas GET");return;}
      String[] parts=line.split(" ");if(parts.length<2){sendError(out,400,"Requisição inválida");return;}
      String path=parts[1];int mark=path.indexOf('?');
      String route=mark<0?path:path.substring(0,mark);
      Map<String,String> params=parseQuery(mark<0?"":path.substring(mark+1));
      if(!active||!token.equals(params.get("token"))){sendError(out,403,"Acesso não autorizado");return;}
      if("/".equals(route)){sendListing(out);return;}
      if(!"/download".equals(route)){sendError(out,404,"Não encontrado");return;}
      int id;
      try{id=Integer.parseInt(params.get("id"));}catch(Exception e){sendError(out,400,"Índice inválido");return;}
      Item item;
      synchronized(this){if(!active||id<0||id>=published.size()){sendError(out,404,"Arquivo indisponível");return;} item=published.get(id);}
      // Reabre o documento autorizado pelo usuário. Nunca recebe URIs ou caminhos arbitrários pela rede.
      try(InputStream stream=activity.getContentResolver().openInputStream(item.uri)){
        if(stream==null)throw new IOException("Arquivo sem leitura");
        String asciiName=item.name.replace("\\","_").replace("\"","_").replace("\r","_").replace("\n","_");
        String hdr="HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\n"+
          "Content-Disposition: attachment; filename=\""+asciiName.replaceAll("[^\\x20-\\x7E]","_")+"\"\r\n"+
          "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(hdr.getBytes("US-ASCII"));
        byte[] buffer=new byte[32768];int len;
        while(active&&(len=stream.read(buffer))!=-1)out.write(buffer,0,len);
        out.flush();
      }catch(Exception ex){android.util.Log.w("ExploradorZIP","Erro no arquivo compartilhado",ex);}
    }catch(Exception e){android.util.Log.w("ExploradorZIP","Cliente desconectado",e);}
  }
  private void sendListing(OutputStream out)throws Exception{
    StringBuilder html=new StringBuilder("<!doctype html><html lang=pt-br><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"+
      "<title>Explorador ZIP — Arquivos</title><style>body{font-family:sans-serif;max-width:740px;margin:25px auto;padding:10px;background:#f5f7fa}a{display:block;padding:14px;border:1px solid #ddd;margin:8px 0;background:white;border-radius:9px;overflow-wrap:anywhere}small{color:#555}</style>"+
      "<h2>Explorador ZIP — Compartilhamento local</h2><p>Somente arquivos selecionados no celular. Acesso temporário e de leitura.</p>");
    synchronized(this){for(int i=0;i<published.size();i++){
      Item f=published.get(i);
      html.append("<a href='/download?token=").append(token).append("&amp;id=").append(i).append("'>⬇ ")
        .append(esc(f.name)).append(" <small>").append(f.size>=0?f.size+" bytes":"tamanho desconhecido").append("</small></a>");
    }}
    html.append("<p>Feche o compartilhamento no aplicativo depois de usar.</p></html>");
    byte[] body=html.toString().getBytes("UTF-8");
    String h="HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+
      "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
    out.write(h.getBytes("US-ASCII"));out.write(body);out.flush();
  }
  private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  private static Map<String,String> parseQuery(String q)throws Exception{
    Map<String,String> map=new HashMap<>();
    for(String entry:q.split("&")){String[] kv=entry.split("=",2);if(kv.length==2)map.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"));}
    return map;
  }
  private static void sendError(OutputStream out,int status,String msg)throws Exception{
    String phrase=status==403?"Forbidden":status==404?"Not Found":status==405?"Method Not Allowed":"Bad Request";
    byte[] body=msg.getBytes("UTF-8");
    out.write(("HTTP/1.0 "+status+" "+phrase+"\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(body);out.flush();
  }
}
