package edu.buffalo.cse.cse486586.groupmessenger2;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Created by sunandan on 3/17/16.
 */
public class PriorityQueueNoDuplicates<Message> extends PriorityBlockingQueue<Message> {
    PriorityQueueNoDuplicates() {

    }
    @Override
    public boolean offer(Message m1) {
        boolean isAdded = false;
        if (super.contains(m1)) {
            return isAdded;
        }
        return super.offer(m1);
    }
    @Override
    public  boolean add(Message m1) {
        boolean isAdded = false;
        if (super.contains(m1)) {
            return isAdded;
        }
        return super.add(m1);
    }

    PriorityQueueNoDuplicates(int initialCapacity,
                                   Comparator<? super Message> comparator) {
        super(initialCapacity,comparator);
    }

}
