import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
* This is just some example code to show you how to interact 
* with the server using the provided client and two queues.
* Feel free to modify this code in any way you like!
*/

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    private static int src = new Random().nextInt(254);
    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency) {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);

        new receiveThread(receivedQueue).start();

        try {
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            int new_line_offset = 0;
            while (true) {
                read = System.in.read(temp.array());
                if(read > 0){
                    if (temp.get(read-1) == '\n' || temp.get(read-1) == '\r' ) {
                        new_line_offset = 1;
                    }
                    if (read > 1 && (temp.get(read-2) == '\n' || temp.get(read-2) == '\r') ) {
                        new_line_offset = 2;
                    }
                    ByteBuffer toSend = ByteBuffer.allocate(read-new_line_offset);
                    toSend.put( temp.array(), 0, read-new_line_offset );
                    Message msg;
                    if( (read-new_line_offset) > 2 ) {
                        msg = new Message(MessageType.DATA, toSend);
                    } else {
                        msg = new Message(MessageType.DATA_SHORT, toSend);
                    }
                    sendingQueue.put(msg);
                }
            }
        } catch (InterruptedException e) {
            System.exit(2);
        } catch (IOException e) {
            System.exit(2);
        }
    }

    public static void main(String args[]) {
        if(args.length > 0) {
            frequency = Integer.parseInt(args[0]);
        }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);        
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            for (int i=8; i<bytesLength; i++) {
                System.out.print( (char) ( bytes.get(i) ) + " " );
            }
            System.out.println();
        }

        public void run() {
            while (true) {
                try{
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY) {
                        System.out.println("BUSY");
                    } else if (m.getType() == MessageType.FREE) {
                        System.out.println("FREE");
                    } else if (m.getType() == MessageType.DATA) {
                        System.out.print("DATA: ");
                        printByteBuffer( m.getData(), m.getData().capacity() );
                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        System.out.print("DATA_SHORT: ");
                        printByteBuffer( m.getData(), m.getData().capacity() );
                    } else if (m.getType() == MessageType.DONE_SENDING) {
                        System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO) {
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING) {
                        System.out.println("SENDING");
                    } else if (m.getType() == MessageType.END) {
                        System.out.println("END");
                        System.exit(0);
                    } else if (m.getType() == MessageType.TOKEN_ACCEPTED) {
                        System.out.println("Token Valid!");
                    } else if (m.getType() == MessageType.TOKEN_REJECTED) {
                        System.out.println("Token Rejected!");
                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: "+e);
                }                
            }
        }
    }
}

