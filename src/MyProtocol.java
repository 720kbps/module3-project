import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    private static final int src = new Random().nextInt(254);
    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency) {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);
        new receiveThread(receivedQueue).start();

        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while (true) {
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println("Read: " + read + " bytes from stdin");
                sendPackets(read, temp);
            }
        } catch (InterruptedException | IOException e){ System.exit(2); }
    }

    public static void main (String args[]) {
        if (args.length > 0) frequency = Integer.parseInt(args[0]);
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            System.out.print("[" +getCurrentTime() + "] ");
            for (int j = 0; j < 6; j++)
            {
                byte charByte = bytes.get(j);
                System.out.print((int) charByte + " ");
            }
            for (int i = 7; i < bytesLength; i++) {
                byte charByte = bytes.get(i);
                System.out.print((char) charByte + " ");
            }
            System.out.println();
        }

        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY) {
                        System.out.println("BUSY");
                    } else if (m.getType() == MessageType.FREE) {
                        System.out.println("FREE");
                    } else if (m.getType() == MessageType.DATA) {
                        System.out.print("DATA: ");
                        printByteBuffer(m.getData(), m.getData().capacity());
                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        System.out.print("DATA_SHORT: ");
                        //printByteBuffer(m.getData(), m.getData().capacity());
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
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }

    /**
     * Formats the time at which the packet was received.
     * @return the current time in the pattern [HH:mm:ss]
     */
    public static String getCurrentTime() {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");
        return currentTime.format(formatter);
    }

    public void sendPackets(int read, ByteBuffer temp) throws InterruptedException {
        int new_line_offset = 0;
        if (read > 0) {
            if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') {
                new_line_offset = 1;
            }
            if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r')) {
                new_line_offset = 2;
            }
            Message msg;
            int position=0;
            while (read > 26) {
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder (toSend, 0, 0, 0,
                               0, false, false, 27);
                toSend.put(temp.array(), position, 26);
                if ((read - new_line_offset) > 2) {
                    msg = new Message(MessageType.DATA, toSend);
                } else {
                    msg = new Message(MessageType.DATA_SHORT, toSend);
                }
                AlohaSend(msg);
                position += 25;
                read -= 25;
            }
            ByteBuffer toSend = ByteBuffer.allocate(32);
            headerBuilder (toSend, 0, 0, 0,
                           0, true, false, read);
            toSend.put(temp.array(), position, read - new_line_offset);
            if ((read - new_line_offset) > 2) {
                msg = new Message(MessageType.DATA, toSend);
            } else {
                msg = new Message(MessageType.DATA_SHORT, toSend);
            }
            AlohaSend(msg);
        }
    }

    public void headerBuilder (ByteBuffer packet, int destination, int seq, int ack,
                               int TTL, boolean FIN, boolean RMS, int length) {
        packet.put((byte) src);
        packet.put((byte) destination);
        packet.put((byte) seq);
        packet.put((byte) ack);
        // Format flags and TTL into a single byte
        packet.put((byte) ((TTL << 4) | (FIN ? 0b10 : 0) | (RMS ? 0b01 : 0)));
        packet.put((byte) length);
    }

    public void AlohaSend(Message msg) throws InterruptedException {
        // The probability grows with the size of the sending queue
        int q = 60 - Math.min(sendingQueue.size(), 10);
        while (true) {
            if (new Random().nextInt(100) < q) {
                sendingQueue.put(msg);
                break;
            }
            Thread.sleep(1000); // 1 second time slot
        }
    }

}


