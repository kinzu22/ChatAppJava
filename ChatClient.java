
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Scanner;

/**
 * 
 * @author anhhct
 * ChatClient.java: The client of the chat application. Has a intro window for setting connection
 * parameters and user name. Has a clients list window showing all the connected clients, this list
 * is received from server. Making connection to a client using that client's info (ip address, port,
 * secret token...) will open a chat window for chatting.
 *
 */


public class ChatClient {

   private static final String DEFAULT_SERVER_HOST = "localhost"; //default server address
   private static final int DEFAULT_SERVER_PORT = 5000; //default communicating port
   
   private static Socket connectionToServer;
   private static ServerSocket listeningSocket;
   private static String secret;  // This client's secret, provided by the server.
   private static String clientName;  // This client's name.
   
   private static boolean running;  // If the connection running
   
   public static void main(String[] args) {
      new IntroWindow();
   }   
   
   public static boolean isRunning() {
      return running;
   }   
   
   //intro window is the window to set up port, clientName,...
   private static class IntroWindow extends JFrame implements ActionListener {

      JButton connectButton, cancelButton;

      JTextField serverInput, portInput, nameInput;  // For getting info from user.
      
      IntroWindow() {
         super("Connect to server...");
         cancelButton = new JButton("Cancel");
         cancelButton.addActionListener(this);
         connectButton = new JButton("Connect");
         connectButton.addActionListener(this);
         serverInput = new JTextField(DEFAULT_SERVER_HOST, 18);
         portInput = new JTextField("" + DEFAULT_SERVER_PORT, 5);
         nameInput = new JTextField("user" + (int)(10000*Math.random()), 10);
         JPanel content = new JPanel();
         setContentPane(content);
         content.setBorder(BorderFactory.createLineBorder(Color.GRAY,3));
         content.setLayout(new GridLayout(4,1,3,3));
         content.setBackground(Color.GRAY);
         JPanel row;
         row = new JPanel();
         row.setLayout(new FlowLayout(FlowLayout.CENTER,5,5));
         row.add(new JLabel("Server name or IP:"));
         row.add(serverInput);
         content.add(row);
         row = new JPanel();
         row.setLayout(new FlowLayout(FlowLayout.CENTER,5,5));
         row.add(new JLabel("Port number on server:"));
         row.add(portInput);
         content.add(row);
         row = new JPanel();
         row.setLayout(new FlowLayout(FlowLayout.CENTER,5,5));
         row.add(new JLabel("Your \"name\":"));
         row.add(nameInput);
         content.add(row);
         row = new JPanel();
         row.setLayout(new FlowLayout(FlowLayout.CENTER,5,5));
         row.add(cancelButton);
         row.add(connectButton);
         content.add(row);
         pack();
         Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
         setLocation( (screenSize.width - getWidth())/2, (screenSize.height - getHeight())/2);
         setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
         setVisible(true);
         nameInput.selectAll();
         nameInput.requestFocus();
      }      
      
      public void actionPerformed(ActionEvent evt) {
         if (evt.getSource() == cancelButton)
            System.exit(0);
         else if (evt.getSource() == connectButton || evt.getSource() == nameInput)
            doConnect(); //connect when press "connect"
      }      
      
      void doConnect() {
         String server = serverInput.getText().trim();
         if (server.length() == 0) {
            JOptionPane.showMessageDialog(this,"Server name can't be empty.");
            return;
         }
         int port;
         try {
            port = Integer.parseInt(portInput.getText());
            if (port <= 0 || port > 65525) //check for valid port
               throw new NumberFormatException();
         }
         catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,"Illegal port number.");
            return;
         }
         clientName = nameInput.getText().trim();
         if (clientName.length() == 0) {
            JOptionPane.showMessageDialog(this,"name can't be empty.");
            return;
         }
         try {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            connectionToServer = new Socket(server,port);
            PrintWriter out = new PrintWriter(connectionToServer.getOutputStream());
            BufferedReader in = new BufferedReader(new InputStreamReader(
                                       connectionToServer.getInputStream()));
            out.println("ChatClient"); //send handshake to server to prove identity
            out.flush();
            if (out.checkError())
               throw new Exception("Error while sending identification info to server.");
            String input = in.readLine();
            if (! "ChatServer".equals(input)) //check the received handshake for server's identity
               throw new Exception("Server did not properly identify itself.");
            out.println(clientName); //send the client's name
            listeningSocket = new ServerSocket(0);  // For accepting chat connection requests
            out.println(listeningSocket.getLocalPort()); //send the port used for chatting with other client
            out.flush();
            if (out.checkError())
               throw new Exception("Error while sending identification info to server.");
            secret = in.readLine();
            if (secret == null)
               throw new Exception("Connection closed unexpectedly by server.");
            new ClientListWindow(in,out);
            dispose();
         }
         catch (Exception e) {
            if (listeningSocket != null) {
               try {
                  listeningSocket.close();
               }
               catch (Exception e2) {
               }
            }
            setCursor(Cursor.getDefaultCursor());
            JOptionPane.showMessageDialog(this,"Can't open connection to server:\n" + e);
         }
      }
      
   }   
   
   // This window show the list of connected clients
   private static class ClientListWindow extends JFrame 
                                 implements ActionListener, ListSelectionListener {
      
      JButton connectButton;
      JButton closeButton;
      
      JList clientList;     // Holds the list of clients.
      volatile ArrayList<ClientInfo> clientInfo; // List of clients shown
      
      PrintWriter out;    
      BufferedReader in;

      Thread readerThread;
      Thread writerThread;
      Thread listeningThread;
      volatile boolean closed;  // Set to true when window and connection to server close.
      volatile long lastRefreshTime;  // Time when client list was last modified.
      
      
      
      ClientListWindow(BufferedReader in, PrintWriter out) {
         super("ChatClient: " + clientName);
         this.in = in;
         this.out = out;
         connectButton = new JButton("Connect to Selected Client");
         connectButton.addActionListener(this);
         connectButton.setEnabled(false);
         closeButton = new JButton("Close all Windows and Quit");
         closeButton.addActionListener(this);
         clientList = new JList();
         clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
         clientList.addListSelectionListener(this);
         JPanel content = new JPanel();
         content.setBackground(Color.GRAY);
         content.setBorder(BorderFactory.createLineBorder(Color.GRAY,3));
         content.setLayout(new BorderLayout(3,3));
         content.add( new JScrollPane(clientList), BorderLayout.CENTER);
         JPanel bottom = new JPanel();
         bottom.setBackground(Color.GRAY);
         bottom.setLayout(new GridLayout(2,1,3,3));
         bottom.add(connectButton);
         bottom.add(closeButton);
         content.add(bottom, BorderLayout.SOUTH);
         setContentPane(content);
         pack();
         Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
         setLocation( screenSize.width - getWidth() - 50, 50);
         setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
         addWindowListener( new WindowAdapter() {
            public void windowClosing(WindowEvent evt) {
               closeConnectionToServer();
            }
         });
         setVisible(true);
         readerThread = new ReaderThread();
         writerThread = new WriterThread();
         listeningThread = new ListeningThread();
         readerThread.start();
         writerThread.start();
         listeningThread.start();
      }      
      
      public void actionPerformed(ActionEvent evt) {
         if (evt.getSource() == closeButton) {
            ChatWindow.closeAll();
            closeConnectionToServer();
         }
         else if (evt.getSource() == connectButton) {
            doConnect();
         }
      }
      
      public void valueChanged(ListSelectionEvent e) {
         int selectedIndex = clientList.getSelectedIndex();
         connectButton.setEnabled( selectedIndex >= 0 );
      }
      
      class ClientInfo {
         String info;    // The client's info string, as received from the server.
         String name;  // The client's name, read from the info string.
         String ip;      // The client's ip address, read from the info string.
         int port;       // The client's port number, read from the info string.
         String secret;  // The client's secret, read from the info string.
         ClientInfo(String info) {
            this.info = info;
            Scanner scanner = new Scanner(info); // For parsing the info string.
            scanner.useDelimiter("~"); // Pieces of info string are separated by "~".
            name = scanner.next();
            ip = scanner.next();
            port = Integer.parseInt(scanner.next());
            secret = scanner.next();
         }
      }
      
      
      // this is call whenever a new list of clients is received
      //to update the list in the window
      synchronized void setClientList(ArrayList<ClientInfo> clientInfo) {
         this.clientInfo = clientInfo;
         String[] listStrings = new String[clientInfo.size()];
         for (int i = 0; i < listStrings.length; i++) {
            ClientInfo info = clientInfo.get(i);
            listStrings[i] = info.name + " (" + info.ip + ")";
         }
         clientList.setListData(listStrings);
      }
      
      // add a client into the list
      synchronized void addClient(ClientInfo info) {
         if (clientInfo == null) {
            ArrayList<ClientInfo> client = new ArrayList<ClientInfo>();
            client.add(info);
            setClientList(client);
         }
         else {
            clientInfo.add(info);
            setClientList(clientInfo);
         }
      }
      
      // and remove one
      synchronized void removeClient(String info) {
         if (clientInfo == null)
            return;
         for (int i = 0; i < clientInfo.size(); i++) {
            if (info.equals(clientInfo.get(i).info)) {
               clientInfo.remove(i);
               setClientList(clientInfo);
               return;
            }
         }
      }
      
      //connect to client
      synchronized void doConnect() {
         int selectedIndex = clientList.getSelectedIndex();
         if (selectedIndex < 0)
            return;
         clientList.clearSelection();
         ClientInfo info = clientInfo.get(selectedIndex);
         new ChatWindow(info.ip, info.port, clientName, info.name, info.secret);
      }

      
      void closeConnectionToServer() {
         closed = true;
         running = false;
         dispose();
         try {
            listeningSocket.close();
         }
         catch (Exception e) {
         }
         try {
            connectionToServer.close();
         }
         catch (Exception e) {
         }
         synchronized(writerThread) {
            writerThread.notify();
         }
         try {
            Thread.sleep(1000);
         }
         catch (InterruptedException e) {
         }
         if (ChatWindow.openWindowCount() == 0)
            System.exit(0);
      }
      
      // thread to accept connection from other client, and open the chat window
      class ListeningThread extends Thread {
         public void run() {
            try {
               while (! closed) {
                  Socket socket = listeningSocket.accept();
                  new ChatWindow(socket,secret);
               }
            }
            catch (Exception e) {
               if (! closed) {
                  JOptionPane.showMessageDialog(ClientListWindow.this,
                        "Listening socket has closed because of an error.\n" +
                        "Can no longer accept incoming connection requests!\n" +
                        "Error: " + e);
               }
            }
         }
      }
      
      // the thread to receive command from server
      class ReaderThread extends Thread {
         public void run() {
            try {
               while (!closed) {
                  String command = in.readLine();
                  if (command == null)
                     throw new Exception();
                  else if (command.equals("addclient")) {  // A client was added.
                     String info = in.readLine();
                     addClient(new ClientInfo(info));
                     lastRefreshTime = System.currentTimeMillis();
                  }
                  else if (command.equals("removeclient")) { // A client was removed.
                     String info = in.readLine();
                     removeClient(info);
                     lastRefreshTime = System.currentTimeMillis();
                  }
                  else if (command.equals("clients")) { // Complete client list.
                     ArrayList<ClientInfo> clients = new ArrayList<ClientInfo>();
                     while (true) {
                        String line = in.readLine();
                        if (line.equals("endclients"))
                           break;
                        clients.add(new ClientInfo(line));
                     }
                     setClientList(clients);
                     lastRefreshTime = System.currentTimeMillis();
                  }
                  else if (command.equals("ping") || command.equals("pingresponse")) { // ignored
                  }
                  else
                     throw new Exception("Illegal data");
               }
            }
            catch (Exception e) {
               if (! closed) {
                  closed = true;
                  JOptionPane.showMessageDialog(ClientListWindow.this,
                        "Error occurred while reading from ChatServer.\n" +
                        "New connections are no longer possible.");
                  closeConnectionToServer();
               }
            }
         }
      }

      // thread to send command to server
      class WriterThread extends Thread {
         public void run() {
            try {
               while (!closed) {
                  synchronized(this) {
                     try {
                        wait(10*60*1000); // Wait 10 minutes or until notify() is called.
                     }
                     catch (InterruptedException e) {
                     }
                     if (! closed) {
                        String send;
                        if (System.currentTimeMillis() - lastRefreshTime > 25*60*1000)
                           send = "refresh";
                        else
                           send = "ping";
                        out.println(send);
                        out.flush();
                        if (out.checkError())
                           throw new Exception();
                     }
                  }
               }
            }
            catch (Exception e) {
               if (! closed) {
                  closed = true;
                  JOptionPane.showMessageDialog(ClientListWindow.this,
                        "Error occurred while sending to ChatServer.\n" +
                        "New connections are no longer possible.");
                  closeConnectionToServer();
               }
            }
         }
      }

   }   

}
