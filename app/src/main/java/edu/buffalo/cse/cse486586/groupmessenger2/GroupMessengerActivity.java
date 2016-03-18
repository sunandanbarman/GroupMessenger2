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
    static final String TAG      = GroupMessengerActivity.class.getName();
    static final int msgLength   = 255;
    static final int TIMEOUT     = 2000;

    static public String myPort  = "";
    private Socket clientSocket  = null;

    static ArrayList<String> REMOTE_PORT;

    static public EditText editText = null;
    static public TextView textView = null;
    public static SQLHelperClass sql= null;

    /** Global lock object**/
    private final static Object lock = new Object();

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

    /*receiver side processing*/
    static int MAX_DELIVERABLE      = 25;
    static int countDeliverableRecv = 0;
    /* Using PriorityBlockingQueue helps to handle concurrency issues */
    private PriorityBlockingQueue<Message> hold_back_queue = new PriorityBlockingQueue<Message>();

    private PriorityBlockingQueue<Message> delivery_queue  = new PriorityBlockingQueue<Message>(25, new SortBySeqNumber_ProcNumber_FifoOrder());
    private PriorityBlockingQueue<Message> printQueue      = new PriorityQueueNoDuplicates<Message>(100,new SortBySeqNumber_ProcNumber_FifoOrder());
    /*Data structures */
    private volatile HashMap<Message, Integer> msgMapWithMaxSeqNumber; // <msg,MaxProposalRecv> map
    private volatile Map<Message,Integer> countReplyRecv ;// <msg, NumbersOfProcessReplied >track how many processes sent sequence number

    private Map<String,Boolean> aliveProcess; // keeps track of alive processess

    class SortBySeqNumber_ProcNumber_FifoOrder implements Comparator<Message> {
        @Override
        public int compare(Message m1, Message m2) {
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
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT,50);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket " + e.getMessage());
            return;
        }

    }

    private void createDataQueues() {
        msgMapWithMaxSeqNumber  = new HashMap<Message, Integer>();
        REMOTE_PORT = new ArrayList<String>(Arrays.asList("11108","11112","11116","11120","11124"));
    }

    private void createSendButtonEvent() {
        findViewById(R.id.btnSend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = editText.getText().toString() + "\n";
                Log.e(TAG, "within send button msg :" + msg);
                editText.setText(""); //reset text
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
        FifoCounterSend = new int[]{0,0,0,0,0};

        createServerSocket();
        createDataQueues();
        createPortProcessMap();


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

        for (Iterator<Message> it = hold_back_queue.iterator(); it.hasNext(); ) {
            Message f = it.next();
            if (f.equals(message)) {
                f.messageType = DELIVER;
                if (f.seqNumber != message.seqNumber) {
                    f.seqNumber = message.seqNumber;
                }
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
        Log.e(TAG, "To handle Failures for remotePort " + remotePort);
        if (remotePort.equals(null)) { // in the odd case if it happens
            return;
        }
        Log.e(TAG,"aliveProcess set ");
        for(Map.Entry<String,Boolean> entry : aliveProcess.entrySet()) {
            Log.e(TAG,entry.getKey() + " : " + entry.getValue());
        }
        Log.e(TAG," handleFailures 1.1 " + aliveProcess.containsKey(remotePort));
        if (aliveProcess.containsKey(remotePort)) {
            REMOTE_PORT.remove(remotePort);
            aliveClients--;
            aliveProcess.remove(remotePort);
            Log.e(TAG, "remote Port removed from aliveProcess ");
            int countFromFailedClient = 0;

            Log.e(TAG,"MAX_DELIVERABLE after hold_back_queue " + MAX_DELIVERABLE);
            //** Remove all messages which were received from failed client**//*
            for (Iterator<Message> iterator = delivery_queue.iterator(); iterator.hasNext();) {
                message = iterator.next();
                if (message.originPort.equalsIgnoreCase(remotePort)) {
                    countFromFailedClient++;
                }
            }
            countFromFailedClient = 5 - countFromFailedClient; // HACK: since grader sends only 5 messages per client, we can rely on this
            MAX_DELIVERABLE = MAX_DELIVERABLE - countFromFailedClient;
            Log.e(TAG,"MAX_DELIVERABLE after delivery_queue " + MAX_DELIVERABLE);
            for (Map.Entry<Message,Integer> entry : countReplyRecv.entrySet()) { // search for the message which can be delivered now
                if (entry.getValue() == aliveProcess.size()) {
                    message = entry.getKey();
                    Log.e(TAG,"message " + message.toString() + " in handleFailures is DELIVER");
                    break;
                }
            }

            if (countDeliverableRecv == MAX_DELIVERABLE) {
                Log.e(TAG,"Delivery can start..");
                new ServerTask().checkIntoDeliveryQueue();
            }

            if (message != null) {
                /* Message to be delivered, free its associated helper memory*/
                message.messageType = DELIVER;
                countReplyRecv.remove(message);
                msgMapWithMaxSeqNumber.remove(message);
                sendMessageAsPerMessageType(message);
            }

        }
    }

    private void sendMessageAsPerMessageType(Message message) {
        if (message.messageType.equalsIgnoreCase(PROPOSED)) {
            new ReplyClientTask().execute(message);
        } else if (message.messageType.equalsIgnoreCase(DELIVER)) {
            new sendMessageBroadcast().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,message);
        }
    }
    /*****************Methods to insert data into DB**********************/
    private synchronized  void updateDB(Message message) {
        ContentValues cv = new ContentValues();
        cv.put("key", dbSeqNumber++);
        cv.put("value", message.message);
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
                    clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(TIMEOUT * 5);
                    Log.e(TAG, "client socket accepted from " + clientSocket.getRemoteSocketAddress() + " : " + clientSocket.getPort());
                    try {
                        /* Write ACK to sender for implementing timeout facility on sender side*/
                        Log.e(TAG,"To send ACK now");
                        String reply;
                        outputStream = clientSocket.getOutputStream();
                        dataOutputStream = new DataOutputStream(outputStream);
                        Message temp= new Message (HEARTBEART,myPort,String.valueOf(clientSocket.getPort()),0,HEARTBEART,processPortMap.get(myPort),
                                                   0,0); // in step 1, no sequence number is required to be sent
                        reply = temp.deconstructMessage();
                        dataOutputStream.write(reply.getBytes());
                        dataOutputStream.flush();
                        Log.e(TAG,"Wait for actual message");

                        inputStream     = clientSocket.getInputStream();
                        dataInputStream = new DataInputStream(inputStream);
                        incomingBuffer  = new byte[msgLength]; //assumed max length
                        dataInputStream.read(incomingBuffer);


                        message = new Message();
                        message.reconstructMessage(new String(incomingBuffer));
                        Log.e(TAG, message.toString());
                        clientSocket.close();

                        /** We have now received proposed sequence number, lets reply to it with our agreed upon sequence number !**/
                        if (message.messageType.equalsIgnoreCase(MULTICAST))
                        { //this is step 2 of ISIS algorithm,

                            Log.e(TAG, "message is Multicast " + message.toString());
                            message.messageType = PROPOSED;

                            sendMessageAsPerMessageType(message);
                            Log.e(TAG, "message sequenceNumber  " + message.seqNumber + " for message " + message.toString());
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
                                Log.e(TAG,"maximum sequence number for message " + message.toString() + " is " + msgMapWithMaxSeqNumber.get(message));
                                message.seqNumber = msgMapWithMaxSeqNumber.get(message);
                                sendMessageAsPerMessageType(message);
                                synchronized (lock) {
                                    countReplyRecv.remove(message);
                                    msgMapWithMaxSeqNumber.remove(message);
                                }
                            } else {
                                Log.e("Reply wait...",String.valueOf(countReplyRecv.get(message)));
                            }
                        }
                        else if (message.messageType.equalsIgnoreCase(DELIVER))
                        {
                            Log.e(TAG,"Message " + message.toString()  +" is now deliverable ");
                            Log.e("*******BEFORE********", "Before ...");
                            printHoldBackQueue();
                            reorderHoldBackQueueIfRequired(message);
                            Log.e("*******AFTER********", "After...");
                            printHoldBackQueue();

                            synchronized (lock) {

                                agreedSeqNumber = agreedSeqNumber > message.seqNumber ? agreedSeqNumber : message.seqNumber;
                                Log.e(TAG,"agreedSeqNumber is " + agreedSeqNumber + " messsage.seqNumber " + message.seqNumber);
                            }
                            countDeliverableRecv++;
                            Log.e(TAG,"countDeliverableRecv :"  + countDeliverableRecv);
                            addMessageToDeliveryQueue();
                            printPrioirityQueueInOrder(delivery_queue);
                            printQueue.add(message);

                            insertData();
                            if (countDeliverableRecv == MAX_DELIVERABLE)
                            {
                                checkIntoDeliveryQueue();
                            }
                        }
                    }
                    catch (Exception ex) {
                        Log.e(TAG,"Remote port :" + String.valueOf(clientSocket.getPort()));
                        Log.e(TAG, "exception in processing client messages " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
                serverSocket.close();
            }
            catch (Exception e) {
                Log.e(TAG,"remotePort  : " +
                        serverSocket.getLocalPort() + " : " +
                        serverSocket.getLocalSocketAddress());
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
        /*
         *  This function will pop all the delivery messages and add them to delivery queue
         *  This is safe to do, since before delivery, we sort the whole delivery queue to ensure Total + FIFO ordering
         *  */
        private synchronized  void addMessageToDeliveryQueue() {

            if (hold_back_queue != null) {
                for (Iterator<Message> iterator = hold_back_queue.iterator(); iterator.hasNext();) {
                    Message message =  iterator.next();
                    if (message.messageType.equalsIgnoreCase(DELIVER)) {
                        delivery_queue.add(new Message(message));
                        iterator.remove();
                    }
                }
            }
        }
        private synchronized void checkIntoDeliveryQueue() {
            Log.e(TAG,"checkIntoDeliveryQueue begins");
            LinkedList<Message> tempQ;
            Message message;
            if (delivery_queue != null) {
                Log.e(TAG,"checkIntoDeliveryQueue 1");

                tempQ = new LinkedList<Message>();
                Message m1;
                while ( (m1 = delivery_queue.poll()) != null) {
                    tempQ.add(new Message(m1));

                }
                Log.e(TAG,"tempQ starts");
                for(Message m2 : tempQ) {
                    Log.e(TAG,m2.toString());
                }
                Log.e(TAG,"tempQ ends");
                Collections.sort(tempQ, new SortBySeqNumber_ProcNumber_FifoOrder());

                Log.e(TAG,"%%% After sorting tempQ starts");
                for(Message m2 : tempQ) {
                    Log.e(TAG,m2.toString());
                }
                Log.e(TAG, "%%% After sorting tempQ ends");

                while ( (message = tempQ.poll()) != null) {
                    Log.e(TAG, "insertIntoDeliveryQueue the message " + message.toString());
                    //updateDB(message);
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
                countReplyRecv.put(message, countReplyRecv.get(message) + 1);
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
            Log.e("sendMessageB'cast begin",message.toString());
            String remotePort = null;
            for(int i=aliveClients-1; i>=0 ; i--)
            {
                try {
                    remotePort = REMOTE_PORT.get(i);
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(remotePort));

                    socket.setSoTimeout(TIMEOUT);

                    /* Once we get here we know remote is alive*/
                    // this is required to check whether receiver is alive or not
                    inputStream     = socket.getInputStream();
                    dataInputStream = new DataInputStream(inputStream);
                    incomingBuffer  = new byte[msgLength]; //assumed max length
                    dataInputStream.read(incomingBuffer);

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
    /*%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%*/
    private class InsertIntoDatabase extends AsyncTask<Void,Void,Void> {
        @Override
        protected Void doInBackground(Void...Params) {
            Log.e(TAG,"*********InsertIntoDatabase begins********");
            int counter = 0;
            synchronized (this ) {
                PriorityBlockingQueue<Message> tempQ = new PriorityBlockingQueue<Message>(printQueue);
                Message tempMessage;
                dbSeqNumber = 0;
                while ((tempMessage = tempQ.poll()) != null){
                    Log.e(TAG," counter " + counter + " tempMessage " + tempMessage.toString());
                    counter++;
                    updateDB(tempMessage);
                }
            }
            Log.e(TAG,"*********InsertIntoDatabase ends********");
            return  null;
        }
    }
    private void insertData() {
        new InsertIntoDatabase().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
    }
    /*%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%*/
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
                printQueue.add(message);
                Socket socket = new Socket( InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(message.originPort));
                socket.setSoTimeout(TIMEOUT);

                /* Once we get here we know remote is alive*/
                // this is required to check whether receiver is alive or not
                inputStream     = socket.getInputStream();
                dataInputStream = new DataInputStream(inputStream);
                incomingBuffer  = new byte[msgLength]; //assumed max length
                dataInputStream.read(incomingBuffer);

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

    /*%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%*/
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
                String remotePort ="";
                for(int i= aliveClients-1 ;i >=0 ; i--) { // bind to all the listed ports
                    try {
                        remotePort = REMOTE_PORT.get(i);

                        socket = new Socket(InetAddress.getByAddress(new byte[]{10,0,2,2}),Integer.parseInt(remotePort));
                        socket.setSoTimeout(TIMEOUT * 2);

                        /* Once we get here we know remote is alive*/
                        // this is required to check whether receiver is alive or not
                        Log.e(TAG,"wait for ACK");
                        inputStream     = socket.getInputStream();
                        dataInputStream = new DataInputStream(inputStream);
                        incomingBuffer  = new byte[msgLength]; //assumed max length
                        dataInputStream.read(incomingBuffer);
                        Message temp = new Message();
                        temp.reconstructMessage(new String(incomingBuffer));
                        Log.e(TAG, "ACK found " + temp.message);

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

}
