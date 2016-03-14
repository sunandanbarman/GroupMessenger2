package edu.buffalo.cse.cse486586.groupmessenger2;

import java.io.Serializable;

/**
 * Created by sunandan on 3/1/16.
 */
public class Message implements Serializable{
    Message(String message, String myPort, String remotePort, int seqNumber,boolean isDeliverable, boolean isMulticast, int originPNum, int destPNum) {
        this.message       = message;
        this.originPort    = myPort;
        this.remotePort    = remotePort;
        this.seqNumber     = seqNumber;
        this.isDeliverable = isDeliverable;
        this.isMulticast   = isMulticast;
        this.originPNum    = originPNum;
        this.destPNum      = destPNum;
        this.timeStamp     = 0;
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
    public long getTimeStamp() {
     return this.timeStamp;
    }
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    /**
     *
     * @return
     */
    public String toString() {
       String result = "";
        return this.message + "=[originPort:"+ this.originPort + ";"
                            + "remotePort:" + this.remotePort + ";"
                            + "seqNumber:" + this.seqNumber + ";"
                            + "originPNum:" + this.originPNum + ";"
                            + "destPNum:" + this.destPNum + ";"
                            + "isDeliverable:" + this.isDeliverable + ";"
                            + "isMulticast:" + this.isMulticast + ";"
                            + "timeStamp:" + this.timeStamp + ";"
                            +  "]";
    }
    String message , originPort, remotePort;
    int seqNumber,  originPNum, destPNum;
    boolean isDeliverable, isMulticast;
    long timeStamp;

}
