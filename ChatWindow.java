import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

import java.io.*;
import java.net.*;
import java.util.ArrayList;

/**
 * 
 * @author anhhct
 * ChatWindow.java: The window for the chat client. This window is open when user connect to another
 * client. it has text field to input message, field for displaying messages along with connection
 * states over times.
 *
 */


public class ChatWindow extends JFrame {   
   private enum ConnectionState { CONNECTING, CONNECTED, CLOSED }; //states of the connection  
   private static Point previousWindowLocation; //use to make new windows at different location from previous ones
   private ConnectionHandler connection;
   private JButton closeButton, clearButton, sendButton;
   private JTextField messageInput; //input box to input chat message
   private JTextArea transcript; //messages display field
   private static ArrayList<ChatWindow> openWindows = new ArrayList<ChatWindow>();
   public static void closeAll() { //close all windows
      Object[] windows = openWindows.toArray();
      for (int i = 0; i < windows.length; i++)
         ((JFrame)windows[i]).dispose();
   }
   public static int openWindowCount() {
      return openWindows.size();
   }
   //constructor, make a new window with socket connected to the buddy client
   public ChatWindow(Socket connectedSocket, String secret) {
      super("Connection Request Received");
      create();
      connection = new ConnectionHandler(connectedSocket,secret);
   }
   public ChatWindow(String hostName, int port, 
                                String myName, String partnerName, String secret) {
      super("Chatting with " + partnerName);
      create();
      connection = new ConnectionHandler(hostName,port,myName,partnerName,secret);
   }
   private void create() {
	   //set up button and layout
      ActionListener actionHandler = new ActionHandler();
      closeButton = new JButton("Close");
      closeButton.addActionListener(actionHandler);
      clearButton = new JButton("Clear");
      clearButton.addActionListener(actionHandler);
      sendButton = new JButton("Send");
      sendButton.addActionListener(actionHandler);
      sendButton.setEnabled(false);
      messageInput = new JTextField();
      messageInput.addActionListener(actionHandler);
      messageInput.setEditable(false);
      transcript = new JTextArea(20,60);
      transcript.setLineWrap(true);
      transcript.setWrapStyleWord(true);
      transcript.setEditable(false);
      
      JPanel content = new JPanel();
      content.setLayout(new BorderLayout(3,3));
      content.setBackground(Color.GRAY);
      JPanel buttonBar = new JPanel();
      buttonBar.setLayout(new GridLayout(1,3,3,3));
      buttonBar.setBackground(Color.GRAY);
      JPanel inputBar = new JPanel();
      inputBar.setLayout(new BorderLayout(3,3));
      inputBar.setBackground(Color.GRAY);
      
      content.setBorder(BorderFactory.createLineBorder(Color.GRAY, 3));
      content.add(buttonBar, BorderLayout.NORTH);
      content.add(inputBar, BorderLayout.SOUTH);
      content.add(new JScrollPane(transcript), BorderLayout.CENTER);
      
      buttonBar.add(clearButton);
      buttonBar.add(closeButton);
      inputBar.add(new JLabel("Your Message:"), BorderLayout.WEST);
      inputBar.add(messageInput, BorderLayout.CENTER);
      inputBar.add(sendButton, BorderLayout.EAST);
      
      setContentPane(content);
      
      pack();
      //make new windows do not cover old ones
      if (previousWindowLocation == null)
         previousWindowLocation = new Point(40,80);
      else {
         Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
         previousWindowLocation.x += 50;
         if (previousWindowLocation.x + getWidth() > screenSize.width)
            previousWindowLocation.x = 10;
         previousWindowLocation.y += 30;
         if (previousWindowLocation.y + getHeight() > screenSize.height)
            previousWindowLocation.y = 50;
      }
      setLocation(previousWindowLocation);
      
      setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
      
      openWindows.add(this);

      addWindowListener( new WindowAdapter() {
         public void windowClosed(WindowEvent evt) {
            if (connection != null && 
                  connection.getConnectionState() != ConnectionState.CLOSED) {
               connection.close();
            }
            openWindows.remove(this);
            if (openWindows.size() == 0 && !ChatClient.isRunning()) {
               try {
                  Thread.sleep(1000);
               }
               catch (InterruptedException e) {
               }
               System.exit(0);
            }
         }
      });      
      setVisible(true);      
   }   
   //actions for buttons
   private class ActionHandler implements ActionListener {
      public void actionPerformed(ActionEvent evt) {
         Object source = evt.getSource();
         if (source == closeButton) {
            dispose();
         }
         else if (source == clearButton) {
            transcript.setText("");
         }
         else if (source == sendButton || source == messageInput) {
            if (connection != null && 
                  connection.getConnectionState() == ConnectionState.CONNECTED) {
               connection.send(messageInput.getText());
               messageInput.selectAll();
               messageInput.requestFocus();
            }
         }
      }
   }  
   
   //get new messages to be displayed in the transcript field with scroll
   private void postMessage(String message) {
      transcript.append(message + "\n");
      transcript.setCaretPosition(transcript.getDocument().getLength());
   } 
   
   //thread managing the connection
   private class ConnectionHandler extends Thread {
      
      private volatile ConnectionState state;
      private String remoteHost; //ip of the connected buddy
      private int port; //working port
      private Socket socket;
      private PrintWriter out; //for communicate
      private BufferedReader in;
      private String secret; //secret provided by server
      private String myName;      
      
      
      //constructors
      
      ConnectionHandler(Socket connectedSocket, String secret) {
         postMessage("ACCEPTING CONNECTION REQUEST...");
         state = ConnectionState.CONNECTED;
         socket = connectedSocket;
         this.secret = secret;
         start();
      }      
      
      ConnectionHandler(String remoteHost, int port, String myName, String partner, String secret) {
         postMessage("CONNECTING TO " + partner +
               " (at " + remoteHost + ", port " + port + ")...");
         state = ConnectionState.CONNECTING;
         this.remoteHost = remoteHost;
         this.port = port;
         this.secret = secret;
         this.myName = myName;
         start();
      }      
      
      synchronized ConnectionState getConnectionState() {
         return state;
      }      
      
      
      //send message to buddy
      synchronized void send(String message) {
         if (state == ConnectionState.CONNECTED) {
            postMessage("SEND:  " + message);
            out.println(message);
            out.flush();
            if (out.checkError()) {
               postMessage("\nERROR OCCURRED WHILE TRYING TO SEND DATA.");
               close();
            }
         }
      }      
      
      //close connection
      synchronized void close() {
         state = ConnectionState.CLOSED;
         try {
            if (socket != null && !socket.isClosed())
               socket.close();
         }
         catch (IOException e) {
         }
      }
      
      //print out the received message
      synchronized private void received(String message) {
         if (state == ConnectionState.CONNECTED)
            postMessage("RECV:  " + message);
      }      
      
      
      //post connection states
      
      synchronized private void connectionOpened() throws IOException {
         postMessage("CONNECTION ESTABLISHED.\n");
         state = ConnectionState.CONNECTED;
         sendButton.setEnabled(true);
         messageInput.setEditable(true);
         messageInput.setText("");
         messageInput.requestFocus();
      }      
      
      synchronized private void connectionClosedFromOtherSide() {
         if (state == ConnectionState.CONNECTED) {
            postMessage("\nCONNECTION CLOSED FROM OTHER SIDE\n");
            state = ConnectionState.CLOSED;
         }
      }      
      
      
      //close sockets as cleanup jobs
      synchronized private void cleanup() {
         state = ConnectionState.CLOSED;
         sendButton.setEnabled(false);
         messageInput.setEditable(false);
         postMessage("\n*** CONNECTION CLOSED ***");
         if (socket != null && !socket.isClosed()) {
            try {
               socket.close();
            }
            catch (IOException e) {
            }
         }
         socket = null;
         in = null;
         out = null;
      }
      
      
      public void run() {
         try {
            if (state == ConnectionState.CONNECTED) {
               InetAddress addr = socket.getInetAddress();
               int port = socket.getPort();
               postMessage("   (from IP address " + addr + ", port " + port +")");
               in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
               out = new PrintWriter(socket.getOutputStream());
               String secret = in.readLine();
               //check whether the buddy has the correct secret provided by server
               //this ensure security, only clients connected via server can make connection
               if (secret == null || !secret.equals(this.secret))
                  throw new Exception("Connection request does not come from a validated user!");
               String partner = in.readLine();
               if (partner == null)
                  throw new Exception("Connection unexpectedly closed from other side.");
               postMessage("Connection opened to " + partner);
               setTitle("Chatting with " + partner);
            }
            else if (state == ConnectionState.CONNECTING) {
                  // The user has requested a request to a remote user.  Open a connection
                  // to the user and send handshake info.
               socket = new Socket(remoteHost,port);
               in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
               out = new PrintWriter(socket.getOutputStream());
               out.println(secret);
               out.println(myName);
               out.flush();
            }
            connectionOpened();  // Set up to use the connection.
            while (state == ConnectionState.CONNECTED) {
                  // Read one line of text from the other side of
                  // the connection, and report it to the user.
               String input = in.readLine();
               if (input == null)
                  connectionClosedFromOtherSide();
               else
                  received(input);  // Report message to user.
            }
         }
         catch (Exception e) {
            if (state != ConnectionState.CLOSED)
               postMessage("\n\n ERROR:  " + e);
         }
         finally {  // Clean up before terminating the thread.
            cleanup();
         }
      }
      
   }
}
