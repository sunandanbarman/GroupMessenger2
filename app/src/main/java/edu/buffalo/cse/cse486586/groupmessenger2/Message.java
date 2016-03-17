package edu.buffalo.cse.cse486586.groupmessenger2;

import java.io.Serializable;

/**
 * Created by sunandan on 3/1/16.
 * Comparable ensures the holdBackQueue always has the lowest sequence number in front
 */
public class Message implements Serializable, Comparable<Message>{
    Message() {

    }
    Message(Message m1) {
        this.message       = m1.message;
        this.originPort    = m1.originPort;
        this.remotePort    = m1.remotePort;
        this.seqNumber     = m1.seqNumber;
        this.messageType   = m1.messageType;
        this.originPNum    = m1.originPNum;
        this.destPNum      = m1.destPNum;
        this.fifoCounter   = m1.fifoCounter;
    }
    Message(String message, String myPort, String remotePort, int seqNumber, String messageType, int originPNum, int destPNum,int FifoCounter) {
        this.message       = message;
        this.originPort    = myPort;
        this.remotePort    = remotePort;
        this.seqNumber     = seqNumber;
        this.messageType   = messageType;
        this.originPNum    = originPNum;
        this.destPNum      = destPNum;
        this.fifoCounter   = FifoCounter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Message message1 = (Message) o;

        if (originPNum != message1.originPNum) return false;
//        if (destPNum != message1.destPNum) return false;
        if (!message.equals(message1.message)) return false;
        //      if (!remotePort.equals(message1.remotePort)) return false;
        return originPort.equals(message1.originPort);

    }

    @Override
    public int hashCode() {
        int result = message.hashCode();
        result = 31 * result + originPort.hashCode();
        //result = 31 * result + remotePort.hashCode();
        //result = 31 * result + destPNum;
        result = 31 * result + originPNum;
        return result;
    }
    public long getFifoCounter() {
        return this.fifoCounter;
    }
    public void setFifoCounter(int fifoCounter) {
        this.fifoCounter = fifoCounter;
    }
    /** split the incomingMessage and fill in the details in "this" object**/
    public void reconstructMessage(String incomingMessage) {
        String[] msgs = incomingMessage.split(";");
        this.message     = msgs[0];
        this.originPort  = msgs[1];
        this.remotePort  = msgs[2];
        this.seqNumber   = Integer.valueOf(msgs[3]);
        this.originPNum  = Integer.valueOf(msgs[4]);
        this.destPNum    = Integer.valueOf(msgs[5]);
        this.messageType = msgs[6];
        this.fifoCounter = Integer.valueOf(msgs[7]);
    }
    /** Use only in client-server communication**/
    public String deconstructMessage() {
        return  this.message     + ";"
                + this.originPort  + ";"
                + this.remotePort  + ";"
                + this.seqNumber   + ";"
                + this.originPNum  + ";"
                + this.destPNum    + ";"
                + this.messageType + ";"
                + this.fifoCounter + ";";
    }
    /**
     *
     * @return
     */
    public String toString() {
        return  "message   :"       + this.message     + ";"
                + "originPort:"     + this.originPort  + ";"
                + "remotePort:"     + this.remotePort  + ";"
                + "seqNumber:"      + this.seqNumber   + ";"
                + "originPNum:"     + this.originPNum  + ";"
                + "destPNum:"       + this.destPNum    + ";"
                + "messageType:"    + this.messageType + ";"
                + "FifoCounter:"    + this.fifoCounter + ";";
    }
    @Override
    public int compareTo(Message another) {

        if(this.seqNumber > another.seqNumber){
            return 1;
        }else if(this.seqNumber < another.seqNumber){
            return -1;
        }
        return 0;
    }

    String message , originPort, remotePort;
    int seqNumber,  originPNum, destPNum;
    String messageType;
    int fifoCounter;

}
