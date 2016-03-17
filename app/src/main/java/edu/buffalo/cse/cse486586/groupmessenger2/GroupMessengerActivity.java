package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;


/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 *
 */
public class GroupMessengerActivity extends Activity {
    /*Constants*/
    static final int SERVER_PORT = 10000;
    //static final String TAG      = GroupMessengerActivity.class.getName();
    static final String TAG      = "TAG"; //TODO : Remove later
    static final int msgLength   = 255;
    static final int TIMEOUT     = 1000;

    static public String myPort  = "";
    private Socket clientSocket  = null;

    static ArrayList<String> REMOTE_PORT;

    static public EditText editText = null;
    static public TextView textView = null;
    public static SQLHelperClass sql= null;

    /** Global lock object**/
    private static Object lock = new Object();

    public static final String MULTICAST = "MULT";
    public static final String PROPOSED  = "PROP";
    public static final String DELIVER   = "DELIVER";
    public static final String HEARTBEART= "HEARTBEAT";

    static volatile int aliveClients = 5; // initialize to 5, decremented as required; NOTE: volatile keyword usage makes sure all threads see this value immediately
    private static Map<String,Integer> processPortMap;
    /** Data structure on sender-side processing**/
    private volatile int sentSeqNumber;
    private volatile int agreedSeqNumber;
    private volatile int dbSeqNumber      = 0; // message # inserted in DB

    private int[] FifoCounterSend; /** Helper data structures for FIFO ordering**/

    private int[] deliverSeqNumber; //keeps track of maximum sequence number received,

    /*receiver side processing*/
    static int MAX_DELIVERABLE      = 25;
    static int countDeliverableRecv = 0;
    /* Using PriorityBlockingQueue helps to handle concurrency issues */
    private PriorityBlockingQueue<Message> hold_back_queue = new PriorityBlockingQueue<Message>();
    private PriorityBlockingQueue<Message> delivery_queue  = new PriorityBlockingQueue<Message>(25, new SortPrintQueue());
    //private PriorityBlockingQueue<Message> finalQueue      = new PriorityBlockingQueue<Message>();
    private PriorityBlockingQueue<Message> printQueue      = new PriorityBlockingQueue<Message>();
    private int[] fifoRecvSeqNumber; //keeps track of maximum sequence number received,
    private HashMap<Message, Integer> msgMapWithMaxSeqNumber;
    private Map<Message,Integer> countReplyRecv ;// track how many processes sent sequence number
    /*private SortedSet<Message> hold_back_queue;
    private LinkedList<Message> delivery_queue;

    */
    private Map<String,Boolean> aliveProcess; // keeps track of alive processess

    class SortPrintQueue implements Comparator<Message> {
        @Override
        public int compare(Message m1,Message m2) {
            if (m1.originPort.compareTo(m2.originPort) > 1) {
                return 1;
            } else if (m1.originPort.compareTo(m2.originPort) < 0) {
                return -1;
            }
            else if (m1.fifoCounter > m2.fifoCounter) {
                return 1;
            } else if (m1.fifoCounter < m2.fifoCounter) {
                return -1;
            }
            return 0;
        }
    }
    class SortBySeqNumber_ProcNumber_FifoOrder implements Comparator<Message> {
        @Override
        public int compare(Message m1, Message m2) {
            if (m1.seqNumber > m2.seqNumber) {
                return 1;
            } else if (m1.seqNumber < m2.seqNumber) {
                return -1;
            }
            if (Integer.valueOf(m1.originPort)  > Integer.valueOf(m2.originPort)) {
                return 1;
            } else if (Integer.valueOf(m1.originPort) < Integer.valueOf(m2.originPort)) {
                return -1;
            }
            if (m1.fifoCounter > m2.fifoCounter) {
                return 1;
            } else if (m1.fifoCounter < m2.fifoCounter) {
                return -1;
            }
            return 0;
        }
    }

    class SortByFifoOrder implements Comparator<Message> {
        @Override
        public int compare(Message m1, Message m2) {
            if (m1.remotePort == m2.remotePort) {
                if (m1.fifoCounter < m2.fifoCounter) {
                    return -1;
                } else if (m1.fifoCounter > m2.fifoCounter) {
                    return 1;
                }
            }
            return 0;
        }
    }

    /**
     * creates map of <port,originPNum> to be used in sending multicast messages
     */
    private static void createPortProcessMap() {
        processPortMap = new HashMap<String, Integer>();
        int counter = 0;
        for(String port:REMOTE_PORT) {
            processPortMap.put(port,counter);
            counter++;
        }
        for(Map.Entry<String,Integer> entry : processPortMap.entrySet()) {
            Log.e(TAG,entry.getKey() + " =>" + entry.getValue());
        }
        Log.e(TAG,"processPortMap size :" + processPortMap.size());
    }

    /**
     *
     */
    private void createServerSocket() {
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket " + e.getMessage());
            return;
        }

    }

    /**
     *A
     */
    private void createDataQueues() {
        msgMapWithMaxSeqNumber  = new HashMap<Message, Integer>();
        REMOTE_PORT = new ArrayList<String>(Arrays.asList("11108","11112","11116","11120","11124"));
/*        hold_back_queue         = new TreeSet<Message>(new SortBySeqNumber_ProcNumber_FifoOrder());
        msgMapWithMaxSeqNumber  = new HashMap<Message, Integer>();
        delivery_queue          = new LinkedList<Message>();*/
        /*queue = new LinkedList[REMOTE_PORT.length];
        for(int i=0; i < REMOTE_PORT.length; i++) {
            queue[i] = new LinkedList<String>();
        }*/
    }
    /**
     *
     */
    private void createSendButtonEvent() {
        findViewById(R.id.btnSend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = editText.getText().toString() + "\n";
                Log.e(TAG, "within send button msg :" + msg);
                editText.setText(""); //reset text
                //textView.append("\n" + msg); //append local message
                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
            }
        });

    }
    private String getMyPort() {
        String myPort;
        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);

        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = String.valueOf((Integer.parseInt(portStr) * 2));
        return myPort;
    }
    private  void initAliveProcessSequence() {
        for(String remotePort : REMOTE_PORT) {
            aliveProcess.put(remotePort,true);
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        sql = SQLHelperClass.getInstance(getApplicationContext());
        //TODO: Add the code for telephony here
        myPort = getMyPort();

        Log.e(TAG, "Local port found " + myPort);
        //appStartTime = new Date();
        FifoCounterSend = new int[]{0,0,0,0,0};

        createServerSocket();
        createDataQueues();
        createPortProcessMap();


       // deliverSeqNumber = new int[REMOTE_PORT.length];
       // lock = new Object();
        fifoRecvSeqNumber = new int[]{-1,-1,-1,-1,-1};
        deliverSeqNumber = new int[]{-1,-1,-1,-1,-1};
        aliveProcess     = new HashMap<String, Boolean>();
        initAliveProcessSequence();
        countReplyRecv   = new HashMap<Message, Integer>();
        sentSeqNumber    = 0;
        agreedSeqNumber  = 0;

        TextView tv = (TextView) findViewById(R.id.textViewSend);
        tv.setMovementMethod(new ScrollingMovementMethod());

        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        editText = (EditText) findViewById(R.id.editText1);
        textView = (TextView) findViewById(R.id.textViewSend);

        createSendButtonEvent();
    }



    /**
     * if the message's sequence number != hold_back_queue sequence number, re-arrange the hold-back queue
     *
     */
    private synchronized  void reorderHoldBackQueueIfRequired(Message message) {

//        if ((hold_back_queue != null) && (hold_back_queue.peek() == message)) { // if message with DELIVER tag is at head of queue, simply deliver it
//            return ;
//        }
        boolean bReorderRequired = false;
        for (Iterator<Message> it = hold_back_queue.iterator(); it.hasNext(); ) {
            Message f = it.next();
            if (f.equals(message)) {
                f.messageType = DELIVER;
                if (f.seqNumber != message.seqNumber) {
                    f.seqNumber = message.seqNumber;
                    bReorderRequired = true;
                }
            }
        }
        if (bReorderRequired) {
            LinkedList<Message> tempQ = new LinkedList<Message>();
            Message m1;
            while ( (m1 = hold_back_queue.poll()) != null) {
                tempQ.add(m1);

            }
            Collections.sort(tempQ,new SortBySeqNumber_ProcNumber_FifoOrder());
            for(Message m2: tempQ) {
                hold_back_queue.add(m2);
            }
        }
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }
    /********************Method to handle failure here***************************************/
    private synchronized void handleFailures(String remotePort) {
        Message message = null;
        Log.e(TAG,"To handle Failures for remotePort " + remotePort);
        if (remotePort.equals(null)) { // in the odd case if it happens
            return;
        }

        if (aliveProcess.containsKey(remotePort)) {
            aliveProcess.remove(remotePort);
            /* Remove from hold_back_queue all the messages sent to failed clients*/
            //Log.e(TAG,"");
            for (Iterator<Message> iterator = hold_back_queue.iterator(); iterator.hasNext();) {
                message = iterator.next();
                if (message.remotePort.equalsIgnoreCase(remotePort)) {
                    iterator.remove();

                    MAX_DELIVERABLE--;
                }
            }
            for (Map.Entry<Message,Integer> entry : countReplyRecv.entrySet()) { // search for the message which can be delivered now
                if (entry.getValue() == aliveProcess.size()) {
                    message = entry.getKey();
                    break;
                }
            }
            if (countDeliverableRecv == MAX_DELIVERABLE) {
                new ServerTask().checkIntoDeliveryQueue();
            }
            //remove the entry from hold_back_queue and countReplyRecv
            //hold_back_queue.remove(message);
            //countReplyRecv.remove(message);
            if (message != null)
                sendMessageAsPerMessageType(message);

        }
    }

    private void sendMessageAsPerMessageType(Message message) {
        if (message.messageType == PROPOSED) {
            new ReplyClientTask().execute(message);
        } else if (message.messageType == DELIVER) {
            new sendMessageBroadcast().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,message);
        }
    }
    /*****************Methods to insert data into DB**********************/
    private void updateDB(Message message) {
        ContentValues cv = new ContentValues();
        cv.put("key", dbSeqNumber++);
        cv.put("value", message.message);
        //cv.put(message,++dbSeqNumber);
        Log.e(TAG,"to insert into db :" + message.toString() + " seq# " + dbSeqNumber);
        sql.insertValues(cv);
    }

    /*%%%%%%%%%%%%%%%%%%%%%%Server Task starts%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%*/
    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            Log.e(TAG,"Within background server function");
            ServerSocket serverSocket = sockets[0];
            OutputStream outputStream;
            DataOutputStream dataOutputStream;
            InputStream inputStream;
            DataInputStream dataInputStream;
            byte[] incomingBuffer;
            Message message;
            try {
                boolean bCanAccept = true;
                while(bCanAccept) {
                    Log.e(TAG,"server socket 1.3");
                    //serverSocket.setSoTimeout(TIMEOUT);
                    clientSocket = serverSocket.accept();
                    //clientSocket.setSoTimeout(TIMEOUT);
                    Log.e(TAG, "client socket accepted from " + clientSocket.getPort());
                    try {
                        /* Write ACK to sender for implementing timeout facility on sender side*/
                        /*Log.e(TAG,"To send ACK now");
                        String reply;
                        outputStream = clientSocket.getOutputStream();
                        dataOutputStream = new DataOutputStream(outputStream);
                        Message temp= new Message (HEARTBEART,myPort,String.valueOf(clientSocket.getPort()),0,HEARTBEART,processPortMap.get(myPort),
                                                   0,0); // in step 1, no sequence number is required to be sent
                        reply = temp.deconstructMessage();
                        dataOutputStream.write(reply.getBytes());
                        dataOutputStream.flush();
                        Log.e(TAG,"Wait for actual message");
                        */
                        inputStream     = clientSocket.getInputStream();
                        dataInputStream = new DataInputStream(inputStream);
                        incomingBuffer  = new byte[msgLength]; //assumed max length
                        dataInputStream.read(incomingBuffer);

                        Log.e(TAG,"message found " + new String(incomingBuffer));

                        message = new Message();
                        message.reconstructMessage(new String(incomingBuffer));
                        if (message == null || message.equals("")) {
                            Log.e(TAG,"message NOT proper");
                        } else {
                            Log.e(TAG, "message received ");

                            Log.e(TAG, message.toString());
                        }

                        clientSocket.close();

                        /** We have now received proposed sequence number, lets reply to it with our agreed upon sequence number !**/
                        if (message.messageType.equalsIgnoreCase(MULTICAST))
                        { //this is step 2 of ISIS algorithm,

                            Log.e(TAG, "message is Multicast " + message.toString());
/*
                            synchronized (lock) {
                                sentSeqNumber = (sentSeqNumber > agreedSeqNumber) ? sentSeqNumber : agreedSeqNumber;
                                sentSeqNumber++;
                                message.seqNumber = sentSeqNumber;
                            }
*/                          message.messageType = PROPOSED;

                            sendMessageAsPerMessageType(message);
                            Log.e(TAG, "message sequenceNumber  " + message.seqNumber + " for message " + message.toString());
                            //Log.e(TAG,"Message " + message.toString() + " sent !" );
                            Log.e(TAG, "message replied to :" + message.toString());

                        }
                        else if (message.messageType.equalsIgnoreCase(PROPOSED))
                        {
                            /** Here the step 3 of ISIS algorithm executes,we check for all the receiving messages keeping track of **/

                            Log.e(TAG, "Message " + message.toString() + " PROPOSED message with sequence " + message.seqNumber);
                            if (checkMaximumReceivedSequenceNumber(message)) {
                                Log.e("check..SeqNumber ->","true ; size :" + countReplyRecv.get(message));

                            }  else {
                                Log.e("check..SeqNumber ->","message remains");
                            }
                            Log.e("check..SeqNumber ->","Number of alive Processes are :" + aliveProcess.size());
                            if (countReplyRecv.get(message) == aliveProcess.size()) { //maximum count reached
                                message.messageType = DELIVER;
                                /*Log.e(TAG,"count limit reached for message " + message.toString());
                                agreedSeqNumber = msgMapWithMaxSeqNumber.get(message);
                                sentSeqNumber   = agreedSeqNumber;
                                message.isDeliverable = true; //message is ready, receivers can send it to their delivery queues if conditions match with hold back queues
                                message.setTimeStamp((new Date()).getTime() - appStartTime.getTime()); // set the message's timestamp at originator side
                                sendMessageBroadcast(message); // broadcast to group
                                countReplyRecv.put(message,0);*/
                                Log.e(TAG,"maximum sequence number for message " + message.toString() + " is " + msgMapWithMaxSeqNumber.get(message));
                                message.seqNumber = msgMapWithMaxSeqNumber.get(message);
                                sendMessageAsPerMessageType(message);
                            } else {
                                Log.e("Reply wait...",String.valueOf(countReplyRecv.get(message)));
                            }
                        }
                        else if (message.messageType.equalsIgnoreCase(DELIVER))
                        {
                            Log.e(TAG,"Message " + message.toString()  +" is now deliverable ");
                            Log.e("*******BEFORE********", "Before ...");
                            printHoldBackQueue();
                            //message.seqNumber = msgMapWithMaxSeqNumber.get(message);
                            reorderHoldBackQueueIfRequired(message);
                            Log.e("*******AFTER********", "After...");
                            printHoldBackQueue();
                            //
                            //if (checkIntoHoldBackQueue(message))
                            {
                                //insertIntoDeliveryQueue(message);
                                synchronized (lock) {

                                    agreedSeqNumber = agreedSeqNumber > message.seqNumber ? agreedSeqNumber : message.seqNumber;
                                    Log.e(TAG,"agreedSeqNumber is " + agreedSeqNumber + " messsage.seqNumber " + message.seqNumber);
                                }
                                countDeliverableRecv++;
                                Log.e(TAG,"countDeliverableRecv :"  + countDeliverableRecv);
                                addMessageToDeliveryQueue(message);
                                printPrioirityQueueInOrder(delivery_queue);
                                if (countDeliverableRecv == MAX_DELIVERABLE)
                                {
                                    checkIntoDeliveryQueue();
                                }
                                // message delivered, free its associated memory
                                countReplyRecv.remove(message);
                                msgMapWithMaxSeqNumber.remove(message);
                            }
                        }
                    }
                    catch (Exception ex) {
                        //handleFailures();
                        Log.e(TAG, "exception in processing client messages " + ex.getMessage());
                        ex.printStackTrace();
                        //bCanAccept = false;
                    }
                }
                serverSocket.close();
            }
            catch (Exception e) {
                Log.e(TAG,"remotePort  : " +
                        serverSocket.getLocalPort() + " : " +
                        serverSocket.getLocalSocketAddress());
                detectFailedClient();
                Log.e(TAG,"Exception in serverSocket.accept " + e.getMessage());
                e.printStackTrace();
            }

            return null;
        }


        protected void onProgressUpdate(String...strings) {
            /*
             * The following code displays what is received in doInBackground().
             */
            String strReceived = strings[0].trim();
            GroupMessengerActivity.textView.append(strReceived + "\n");
            GroupMessengerActivity.textView.append("\n");
        }
        /** Use only for debugging purpose**/
        private synchronized void printPrioirityQueueInOrder(PriorityBlockingQueue<Message> priQueue) {
            LinkedList<Message> tempQ = new LinkedList<Message>();
            Message message;
            while ((message = priQueue.poll()) != null) {
                tempQ.add(message);
            }
            int counter = 0;
            for(Message message1 : tempQ) {
                Log.e(TAG,message1.toString() + " counter " + counter);
                counter++;
                priQueue.add(message1);
            }
        }
        private synchronized  void printHoldBackQueue() {
            //int counter = 0;
            Log.e(TAG,"***********Hold back queue starts***********");
            printPrioirityQueueInOrder(hold_back_queue);
            Log.e(TAG, "***********Hold back queue ends*************");

        }
        private synchronized  void addMessageToDeliveryQueue(Message message) {
            if (hold_back_queue != null) {
                while (!hold_back_queue.isEmpty() && hold_back_queue.peek().messageType.equalsIgnoreCase(DELIVER))
                {
                    delivery_queue.add(new Message(hold_back_queue.poll()));
                }
            }
        }
        private synchronized void checkIntoDeliveryQueue() {
            Log.e(TAG,"checkIntoDeliveryQueue begins");
            LinkedList<Message> tempQ;
            Message message;
            if (hold_back_queue != null) {
                Log.e(TAG,"checkIntoDeliveryQueue 1");


                Log.e(TAG, "*******delivery queue starts*********");

                //if (bReorderRequired)
                {
                    tempQ = new LinkedList<Message>();
                    Message m1;
                    while ( (m1 = delivery_queue.poll()) != null) {
                        tempQ.add(m1);

                    }
                    Collections.sort(tempQ,new SortBySeqNumber_ProcNumber_FifoOrder());
                    for(Message m2: tempQ) {
                        delivery_queue.add(m2);
                    }
                }

                printPrioirityQueueInOrder(delivery_queue);
                Log.e(TAG, "*******delivery queue ends*********");

                while ( (message = delivery_queue.poll()) != null) {
                    Log.e(TAG,"insertIntoDeliveryQueue the message " + message.toString());
                    updateDB(message);
                    publishProgress(message.message);
                }

            } else {
                Log.e(TAG,"hold_back_queue found null");
            }
            Log.e(TAG,"checkIntoDeliveryQueue ends");
        }

        /** Keeps track of maximum received sequence number, once reply has been received from all 5 processes, we can set agreedSeqNumber
         *  Also, we can reset the sentSeqNumber
         * **/
        private synchronized  boolean checkMaximumReceivedSequenceNumber(Message message) {
            boolean bRes = false;

            Log.e("check..SeqNumber ->",message.toString());
            Log.e(TAG,"countReplyRecv.size() :" + countReplyRecv.get(message));
            for(Map.Entry<Message,Integer> entry : countReplyRecv.entrySet()) {
                Log.e("message & sequence",entry.getKey().toString() + " ***: seq number:" + entry.getValue());
            }

            /*if (countReplyRecv.size() >= aliveProcess.size()) { //fail-safe condition TODO : Check when this condition may occur
                return bRes;
            }*/
           /* Log.e("check..SeqNumber ->","1.1");
            for(Map.Entry<String,Boolean> entry : aliveProcess.entrySet()) {
                Log.e("Process port", entry.getKey() + " alive =" + entry.getValue());
            }*/
            /*if (!aliveProcess.containsKey(message.originPort)) { // another fail-safe condition, TODO: Check when this condition may occur
                return bRes;
            }*/
            //Log.e("check..SeqNumber ->","");
            if (!msgMapWithMaxSeqNumber.containsKey(message)) {
                msgMapWithMaxSeqNumber.put(new Message(message), message.seqNumber);
                countReplyRecv.put(new Message(message), 1);
                Log.e("check..SeqNumber ->", "First time found message");
                bRes = true;

            } else {
                /** '<=' is important here, since in initial state, if only 1 process sends out a message, the
                 sequence number is same for everyone**/
                if(msgMapWithMaxSeqNumber.get(message) <= message.seqNumber ) {
                    msgMapWithMaxSeqNumber.put(message, message.seqNumber);
                }
                //Log.e("check..SeqNumber ->", "1.3.1 size :" + countReplyRecv.get(message));
                countReplyRecv.put(message, countReplyRecv.get(message) + 1);
                //Log.e("check..SeqNumber ->", "1.4");
                bRes = true;

            }

            return bRes;

        }

    }
    /*%%%%%%%%%%%%%%%%%%%%%%%%%%%%%Client tasks start here%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%*/
    private class sendMessageBroadcast extends AsyncTask<Message, Void, Void> {
        @Override
        protected Void doInBackground(Message... messages) {
            Socket socket;
            OutputStream outputStream;
            DataOutputStream dataOutputStream;
            InputStream inputStream;
            DataInputStream dataInputStream;
            byte[] incomingBuffer;
            Message message = messages[0];
            Message temp    ;
            countReplyRecv.put(message,0); // reset counter for the message
            //Set<String> aliveProcessPorts = new HashSet<String>(aliveProcess.keySet());
            Log.e("sendMessageB'cast begin",message.toString());
            String remotePort = null;
            //for(String remotePort : aliveProcessPorts )
            for(int i=aliveClients-1; i>=0 ; i--)
            {
                try {
                    remotePort = REMOTE_PORT.get(i);
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(remotePort));

                    socket.setSoTimeout(TIMEOUT);

                    /* Once we get here we know remote is alive*/
                    // this is required to check whether receiver is alive or not
                    /*inputStream     = socket.getInputStream();
                    dataInputStream = new DataInputStream(inputStream);
                    incomingBuffer  = new byte[msgLength]; //assumed max length
                    dataInputStream.read(incomingBuffer);*/

                    temp = new Message(message);
                    temp.remotePort = remotePort; // set remotePort to the intended recipient, helps in debugging

                    Log.e(TAG, "sending to remotePort " + remotePort);
                    outputStream = socket.getOutputStream();
                    dataOutputStream = new DataOutputStream(outputStream);
                    dataOutputStream.write(temp.deconstructMessage().getBytes());


                    Log.e("sendMessageBroadcast ", "Message " + message.toString() + " sent !");
                    socket.close();
                } catch(SocketTimeoutException ex) {
                    ex.printStackTrace();
                    handleFailures(remotePort);
                    Log.e(TAG, "SocketTimeoutException in sendMessageBroadcast " + message.toString()  + " reason " + ex.getMessage());
                } catch(UnknownHostException ex) {
                    ex.printStackTrace();
                    handleFailures(remotePort);
                    Log.e(TAG, "UnknownHostException in sendMessageBroadcast for " + message.toString()  + " reason " + ex.getMessage());

                } catch (IOException ex) {
                    ex.printStackTrace();
                    handleFailures(remotePort);
                    Log.e(TAG, "IOException in sendMessageBroadcast for origin :" + message.toString()  + " reason " + ex.getMessage());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    //handleFailures(remotePort);
                    Log.e(TAG, "Exception in sendMessageBroadcast for " + message.toString()  + " reason " + ex.getMessage());
                }

            }

            return null;
        }
    }

    private class ReplyClientTask extends AsyncTask<Message, Void, Void>{
        @Override
        protected Void doInBackground(Message... messages) {
            Message message = messages[0];
            InputStream inputStream;
            DataInputStream dataInputStream;
            OutputStream outputStream;
            DataOutputStream dataOutputStream;
            String msgToSend;
            byte[] incomingBuffer;
            Log.e("ReplyClientTask exec",message.toString());

            try {

                synchronized (lock) {
                    sentSeqNumber = (sentSeqNumber > agreedSeqNumber) ? sentSeqNumber : agreedSeqNumber;
                    sentSeqNumber++;
                    message.seqNumber = sentSeqNumber;
                    message.messageType = PROPOSED;
                    message.remotePort = myPort;
                }
                Log.e(TAG, "message sequenceNumber  " + message.seqNumber);
                hold_back_queue.add(new Message(message));
                //printQueue.add(message);
                //insertData(); //

                Socket socket = new Socket( InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(message.originPort));
                socket.setSoTimeout(TIMEOUT);

                /* Once we get here we know remote is alive*/
                // this is required to check whether receiver is alive or not
                /*inputStream     = socket.getInputStream();
                dataInputStream = new DataInputStream(inputStream);
                incomingBuffer  = new byte[msgLength]; //assumed max length
                dataInputStream.read(incomingBuffer);*/

                msgToSend = message.deconstructMessage(); //get string representation
                outputStream = socket.getOutputStream();
                dataOutputStream = new DataOutputStream(outputStream);
                dataOutputStream.write(msgToSend.getBytes());



                socket.close();
            } catch(SocketTimeoutException ex) {
                handleFailures(message.originPort);
                Log.e(TAG, "SocketTimeoutException in ReplyClientTask for origin :" + message.originPort
                        + " dest :" + message.remotePort + " destPNum :" + message.destPNum + " reason " + ex.getMessage());
            } catch(UnknownHostException ex) {
                handleFailures(message.originPort);
                Log.e(TAG, "UnknownHostException in ReplyClientTask for origin :" + message.originPort
                        + " dest :" + message.remotePort + " destPNum :" + message.destPNum + " reason " + ex.getMessage());

            } catch (IOException ex) {
                handleFailures(message.originPort);
                Log.e(TAG, "IOException in ReplyClientTask for origin :" + message.originPort
                        + " dest :" + message.remotePort + " destPNum :" + message.destPNum + " reason " + ex.getMessage());
            } catch (Exception ex) {
                handleFailures(message.originPort);
                Log.e(TAG, "Exception in ReplyClientTask for origin :" + message.originPort
                        + " dest :" + message.remotePort + " destPNum :" + message.destPNum + " reason " + ex.getMessage());
            }

            return null;
        }
    }


    /** Trying to send bytes now directly, instead of serialized objects**/
    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
                Message message;
                Socket socket  ;
                String msgToSend;
                OutputStream outputStream;
                InputStream inputStream;
                DataInputStream dataInputStream;
                byte[] incomingBuffer;
                DataOutputStream dataOutputStream;
                //Set<String> remotePortSet = new HashSet<String>(aliveProcess.keySet());
                String remotePort ="";
                for(int i= aliveClients-1 ;i >=0 ; i--) { // bind to all the listed ports
                    try {
                        remotePort = REMOTE_PORT.get(i);

                        socket = new Socket(InetAddress.getByAddress(new byte[]{10,0,2,2}),Integer.parseInt(remotePort));
                        socket.setSoTimeout(TIMEOUT);

                        /* Once we get here we know remote is alive*/
                        // this is required to check whether receiver is alive or not
                        /*Log.e(TAG,"wait for ACK");
                        inputStream     = socket.getInputStream();
                        dataInputStream = new DataInputStream(inputStream);
                        incomingBuffer  = new byte[msgLength]; //assumed max length
                        dataInputStream.read(incomingBuffer);
                        Message temp = new Message();
                        temp.reconstructMessage(new String(incomingBuffer));
                        Log.e(TAG, "ACK found " + temp.message);
                        */
                        message= new Message (msgs[0],myPort,remotePort,0,MULTICAST,processPortMap.get(myPort),
                                processPortMap.get(remotePort),FifoCounterSend[processPortMap.get(remotePort)]); // in step 1, no sequence number is required to be sent
                        msgToSend = message.deconstructMessage();
                        FifoCounterSend[processPortMap.get(remotePort)]++;

                        Log.e(TAG,"to send the actual message");
                        outputStream = socket.getOutputStream();
                        dataOutputStream = new DataOutputStream(outputStream);
                        dataOutputStream.write(msgToSend.getBytes());


                        Log.e(TAG, "Process number " + remotePort + " is alive.");
                        Log.e(TAG, "Sending message from " + myPort + " to port " + remotePort + " for seq number agreement");
                        socket.close();

                    } catch( SocketTimeoutException ex) {
                        ex.printStackTrace();
                        Log.e(TAG, "process " + remotePort + " is dead !! ");
                        handleFailures(remotePort);
                        Log.e(TAG, "ClientTask SocketTimeoutException " + ex.getMessage());

                    } catch (UnknownHostException ex) {
                        ex.printStackTrace();
                        Log.e(TAG, "process " + remotePort + " is dead !! ");
                        handleFailures(remotePort);
                        Log.e(TAG, "ClientTask UnknownHostException " + ex.getMessage());

                    } catch (IOException ex) {
                        Log.e(TAG, "process " + remotePort + " is dead !! ");
                        handleFailures(remotePort);
                        ex.printStackTrace();
                        Log.e(TAG, "ClientTask socket IOException " + ex.getMessage());

                    } catch (Exception ex) {
                        ex.printStackTrace();
                        Log.e(TAG, "process " + remotePort + " is dead !! ");
                        handleFailures(remotePort);
                        Log.e(TAG, "ClientTask socket Exception " + ex.getMessage());
                    }

                }
            return null;
        }
    }

    /**
     *
     */

}
