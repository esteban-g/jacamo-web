package jacamo.web;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.tyrus.server.Server;

import jacamo.rest.JCMRest;
import jacamo.rest.JCMRuntimeServices;
import jacamo.web.config.WebAppConfig;
import jason.runtime.RuntimeServicesFactory;

@ServerEndpoint(value = "/messages")
public class JCMWeb extends JCMRest {

    private final static Set<Session> sessions = new HashSet<>();
    @SuppressWarnings("unused")
    private static Server ws;
    
    @Override
    public void init(String[] args) throws Exception {
        
        // change the runtimeservices
        RuntimeServicesFactory.set( new JCMRuntimeServices() );
        
        // adds RestAgArch as default ag arch when using this platform
        RuntimeServicesFactory.get().registerDefaultAgArch(WebAgArch.class.getName());
        
        int restPort = 3280;
        int zkPort   = 2181;
        boolean useZK = false;

        // Used when deploying on heroku
        String webPort = System.getenv("PORT");
        if (webPort == null || webPort.isEmpty()) {
            restPort = 8080;
        } else {
            restPort = Integer.parseInt(webPort);
        }
        
        if (args.length > 0) {
            String la = "";
            for (String a: args[0].split(" ")) {
                if (la.equals("--restPort"))
                    try {
                        restPort = Integer.parseInt(a);
                    } catch (Exception e) {
                        System.err.println("The argument for restPort is not a number.");
                    }

                if (a.equals("--main")) {
                    useZK = true;
                }
                if (la.equals("--main"))
                    try {
                        zkPort = Integer.parseInt(a);
                    } catch (Exception e) {
                        System.err.println("The argument for restPort is not a number.");
                    }

                if (la.equals("--connect")) {
                    zkHost = a;
                    useZK = true;
                }
                la = a;
            }           
        }
        
        restHttpServer = startRestServer(restPort,0);
        
        if (useZK) {
            if (zkHost == null) {
                zkFactory  = startZookeeper(zkPort);
                System.out.println("Platform (zookeeper) started on "+zkHost);
            } else {
                System.out.println("Platform (zookeeper) running on "+zkHost);
            }
        }
        
    }
    
    @Override
    public HttpServer startRestServer(int port, int tryc) {
        if (tryc > 20) {
            System.err.println("Error starting web server!");
            return null;
        }
        try {
            restServerURI = UriBuilder.fromUri("http://"+InetAddress.getLocalHost().getHostAddress()+"/").port(port).build();
            
            // registering resources
            WebAppConfig rc = new WebAppConfig();
            
            // get a server from factory
            HttpServer s = GrizzlyHttpServerFactory.createHttpServer(restServerURI, rc);
            
            // other possiblecontainers:
            System.out.println("jacamo-web Rest API is running on "+restServerURI);
            
            ws = startWebsocketServer(8026);
            
            return s;
        } catch (javax.ws.rs.ProcessingException e) {           
            System.out.println("trying next port for rest server "+(port+1)+". e="+e);
            return startRestServer(port+1,tryc+1);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public Server startWebsocketServer(int port) {
        try {
            Server ws = new Server(InetAddress.getLocalHost().getHostAddress(), port, "/ws", JCMWeb.class);
            ws.start();            

            URI wsURI = UriBuilder.fromUri("http://"+InetAddress.getLocalHost().getHostAddress()+"/ws").port(port).build();
            System.out.println("Websockets started on " + wsURI);
            
            return ws;
        } catch (javax.ws.rs.ProcessingException e) {           
            System.out.println("Unable to start websockets on "+port+". e="+e);
            return null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    public void openBrowser() {
        String url = JCMRest.getRestHost();
        
        if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)){
            Desktop desktop = Desktop.getDesktop();
            try {
                desktop.browse(new URI(url));
            } catch (IOException | URISyntaxException e) {
                e.printStackTrace();
            }
        }else{
            Runtime runtime = Runtime.getRuntime();
            try {
                runtime.exec("xdg-open " + url);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        System.out.println("{" + session.getId() + "} Session has been closed.");
        JCMWeb.sessions.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.out.println("{" + session.getId() + "} An error has been detected: " + t.getMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("{" + session.getId() + "} message received: {" + message + "}");
    }

    @OnOpen
    public void onOpen(Session session) {
        JCMWeb.sessions.add(session);
        System.out.println("{" + session.getId() + "} Session opened.");
        try {
            session.getBasicRemote().sendText("Connection Established");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void sendMessage(String message) {
        try {
            if (JCMWeb.sessions.size() > 0) {
                for (Session session: JCMWeb.sessions) {
                    System.out.println("{" + session.getId() + "} Sending message: " + message);
                    session.getBasicRemote().sendText(message);
                }
            } else {
                System.out.println("There is no active websocket session to send the message: " + message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
