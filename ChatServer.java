import java.io.*;
import java.net.*;
import java.util.ArrayList;

/**
 * 
 * @author anhhct
 * ChatServer.java : The server of the chat application, is responsible for waiting connection from 
 * clients, manage a list of connected clients with their secret token, send the list with clients' info
 * to all connected clients so that they can make their own connections with others for chatting.
 * Run as threads for continuous data sending and receiving.
 * The server run as a command-line application
 *
 */


public class ChatServer {
   
   private static final int DEFAULT_PORT = 5000; //the default communicating port
   
   private static String shutdownString; //shutdown signal

   private static int listeningPort;
   private static ServerSocket listener;  
   
   private static ClientList clients; //list of connected clients
   
   private volatile static boolean isShutDown;  //whether the server is shut down  
   
   public static void main(String[] args) {
      listeningPort = DEFAULT_PORT;
      if (args.length > 0) { //if port is given via command line
         try {
             int p = Integer.parseInt(args[0]);
             if (p <= 0 || p > 65535) //valid port or not
                throw new NumberFormatException();
             listeningPort = p;
         }
         catch (NumberFormatException e) {
         }
      }
      try {
         listener = new ServerSocket(listeningPort);
      }
      catch (Exception e) {
         System.out.println("Can't create listening socket on port " + listeningPort);
         System.exit(1);
      }
      System.out.println("Listening on port " + listeningPort);
      clients = new ClientList();
      try {
         while (true) { // Listen until error occurs or socket is closed.
            Socket socket = listener.accept();
            clients.add( socket );
         }
      }
      catch (Throwable e) {
         if (!isShutDown) { // Don't report an error after normal shutdown.
            System.out.println("Server closed with error:");
            System.out.println(e);
         }
      }
      finally {
         System.out.println("Shutting down.");
         clients.shutDown();
      }
   }
   
   //Socket in java use getInetAddress to get the IP address represented by java's InetAddress object
   //This helps convert it to string like xxx.xxx.xxx.xxx
   private static String convertAddress(InetAddress ip) {
      byte[] bytes = ip.getAddress();
      if (bytes.length == 4) {
         String addr = "" + ( (int)bytes[0] & 0xFF );
         for (int i = 1; i < 4; i++)
            addr += "." + ( (int)bytes[i] & 0xFF );
         return addr;
      }
      else if (bytes.length == 16) {
         String[] hex = new String[16];
         for (int i = 0; i < 16; i++)
            hex[i] = Integer.toHexString( (int)bytes[i] & 0xFF );
         String addr = "" + hex[0] + hex[1];
         for (int i = 2; i < 16; i += 2)
            addr += ":" + hex[i] + hex[i+1];
         return addr;
      }
      else
         throw new IllegalArgumentException("Unknown IP address type");
   }
   
   
  
   private static class ClientList { //A list of client objects
      
      ArrayList<Client> clientList = new ArrayList<Client>(); // The clients.      
      
      synchronized void add(Socket socket) { //add a client
         Client c = new Client(socket);
         System.out.println("Client " + c.clientNumber + " created.");
         clientList.add(c);
      }      
      
      synchronized void remove(Client client) { //remove a client
         System.out.println("Client " + client.clientNumber + " removed.");
         if (!isShutDown && clientList.remove(client) && client.info != null) {
            for (Client c : clientList)
               c.clientRemoved(client); //announce that the client is removed
         }
      }
      
      // announce when a new client is connectd
      synchronized void announceConnection(Client newlyConnectedClient) {
    	  //print server log
         System.out.println("Client " + newlyConnectedClient.clientNumber + 
               " connection established with info " + newlyConnectedClient.info);
         for (Client c : clientList)
            c.clientAdded(newlyConnectedClient); //and announce to other clients
      }
      
      // make a copy of the clients list to be use in other function
      //because the original list may change asynchronously because of threads
      synchronized ArrayList<Client> copy() {
         ArrayList<Client> c = new ArrayList<Client>();
         for (Client client : clientList)
            c.add(client);
         return c;
      }
      
      //shut down all connections
      synchronized void shutDown() {
         for (Client client : clientList)
            client.shutDown();
      }
      
   }
   
   // Represent a client
   private static class Client {
      
      static int clientsCreated; //number of client created
      int clientNumber;  //each client has a specific representative number
      volatile String info; //info of a client, made of the form clientName~ip~port~secret;
      String messageOut = ""; // Message waiting to be sent by writer thread.
      ClientThread clientThread; 
      ReaderThread readerThread;
      String secret;
      Socket socket;
      volatile boolean connected;
      volatile boolean closed;      
      
      //constructor
      Client(Socket socket) {
         clientsCreated++;
         clientNumber = clientsCreated;
         secret = clientNumber + "!" + Math.random();
         this.socket = socket;
         clientThread = new ClientThread();
         clientThread.start();
      }      
      
      
      //announce other clients about removal of a client
      //by sending the handle "removeclient\n" to be parse as a command at the client side
      //along with info of the removed client
      void clientRemoved(Client c) {
         if (c != this) {
            send("removeclient\n" + c.info + '\n');
         }
      }      
      
      //announce other clients about a newly added client
      //by sending handle "removeclient\n" and the added client's info
      void clientAdded(Client c) {
         if (c != this) {
            send("addclient\n" + c.info + '\n');
         }
      }      
      
      synchronized void shutDown() { //shut down the connection
         if (! closed) {
            closed = true;
            try {
               socket.close();
            }
            catch (Exception e) {
            }
            synchronized(this) {
               notify();
            }
         }
      }      
      
      synchronized void close() { //close connection and remove this client from client list
         if (!closed) {
            closed = true;
            try {
               socket.close();
            }
            catch (Exception e) {
            }
            notify();
            clients.remove(this);
         }
      }      
      
      synchronized void send(String message) { //send a message out
         messageOut += message; //by changing the messageOut variable
         notify(); //then wake up the thread. Java will handle the thread itself so that this does not
         			//block any working job
      }      
      
      synchronized void sendClientList() { //send out the list of clients to all clients connected
         ArrayList<Client> c = clients.copy();
         messageOut += "clients\n";
         for (Client client : c)
            if (client != this)
               messageOut += client.info + '\n'; 
         messageOut += "endclients\n";
         notify();
      }      
      
      
      //this is the main client thread, which sets up connection, start the reader thread at the server sidd
      // and write message to clients
      class ClientThread extends Thread {
         public void run() {
            try {
               String ip = convertAddress(socket.getInetAddress());
               PrintWriter out;
               BufferedReader in;
               out = new PrintWriter(socket.getOutputStream());
               in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
               out.println("ChatServer"); //print out handshake to be sure of the connection
               out.flush();
               if (out.checkError())
                  throw new Exception("Error while trying to send handshake to client");
               String handshake = in.readLine();
               if (! "ChatClient".equals(handshake)) //client also sends handshake to prove it identity
                  throw new Exception("Client did not properly identify itself.");
               String handle = in.readLine();
               if (handle.equals(shutdownString)) {
                  out.println("shutting down");
                  out.flush();
                  isShutDown = true;
                  listener.close();
                  return;
               }
               handle = handle.replaceAll("~","-"); //make sure handle does not have unwanted symbol
               String portString = in.readLine();
               int port;
               try {
                  port = Integer.parseInt(portString);
               }
               catch (NumberFormatException e) {
                  throw new Exception("Did not receive port number from client.");
               }
               if (port <= 0 || port > 65535)
                  throw new Exception("Illegal port number received from client.");
               out.println(secret);
               out.flush();
               if (out.checkError())
                  throw new Exception("Error while sending initial data to client.");
               info = handle + "~" + ip + "~" + port + "~" + secret;
               info = info.replaceAll(" ","_");
               connected = true;
               clients.announceConnection(Client.this);
               readerThread = new ReaderThread(in);
               readerThread.start();
               sendClientList(); //first send the list of clients
               while (!closed && !isShutDown) {
                  String messageToSend;
                  synchronized(Client.this) {
                     messageToSend = messageOut;
                     messageOut = "";
                  }
                  if (closed || isShutDown)
                     break;
                  if (messageToSend.length() == 0)
                     messageToSend = "ping\n"; 
                  out.print(messageToSend);
                  out.flush();
                  if (out.checkError())
                     throw new Exception("Error while sending to client.");
                  synchronized(Client.this) {
                     if (!closed && !isShutDown && messageOut.length() == 0) {
                        try { // sleep for about 10 minutes or until notified of a new message.
                           Client.this.wait(10*(50+(int)(15*Math.random()))*1000);
                        }
                        catch (InterruptedException e) {
                        }
                     }
                  }
               }
            }
            catch (Exception e) {
               if (!closed && ! isShutDown)
                  System.out.println("Client " + clientNumber + " error: " + e);
            }
            finally {
               close();
            }
         }
      }      
      
      
      //the reader thread to read from clients and make answer
      class ReaderThread extends Thread {
         BufferedReader in;
         ReaderThread(BufferedReader in) {
            this.in = in;
         }
         public void run() {
            try {
               while (true) {
                  String messageIn = in.readLine();
                  if (messageIn == null)
                     break;  // connection closed from other side
                  else if (messageIn.equals("ping")) //ping is implemented if network check is needed
                     send("pingresponse\n");
                  else if (messageIn.equals("refresh")) //if client sends refresh command,
                	  									//then send back the newest clients list
                     sendClientList();
                  else
                     throw new Exception("Illegal data received from client");
               }
            }
            catch (Exception e) {
               if (!closed && !isShutDown)
                  System.out.println("Client " + clientNumber + " error: " + e);
            }
            finally {
               close();
            }
         }
      } 
      
   }   

}
