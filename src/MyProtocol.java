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

    private static int src = new Random().nextInt(254);
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
            int new_line_offset = 0;
            while(true){
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println(
                        "Read: " + read + " bytes from stdin"
                );
                if(read > 0) {
                    // Check if last char a return or newline, so we can strip it
                    if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') new_line_offset = 1;
                    // Check if second to last char is a return or newline, so we can strip it
                    if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r'))
                        new_line_offset = 2;
                    Message msg;
                    int position=0; //pozitia din care incepe sa trimita pachetul
                    //asta imparte mesajul in packet-uri de 32 de bytes
                    while (read > 32) {
                        ByteBuffer toSend = ByteBuffer.allocate(32);
                        toSend.put((byte) (31));
                        // enter data without newline / returns
                        toSend.put(temp.array(), position, 31); //poate 31
                        if ((read - new_line_offset) > 2) {
                            msg = new Message(MessageType.DATA, toSend);
                        } else {
                            msg = new Message(MessageType.DATA_SHORT, toSend);
                        }
                        sendingQueue.put(msg);
                        //eu hz dc aici trb sa fie 30 da daca lucreaza nu ma jalui
                        position+=30;
                        read-= 30;
                    }

                    // asta face ultimul packet de size mai mic <32
                    ByteBuffer toSend = ByteBuffer.allocate(read - new_line_offset+1);
                    toSend.put((byte) (read));
                    toSend.put(temp.array(), position, read - new_line_offset);
                    if ((read - new_line_offset) > 2) {
                        msg = new Message(MessageType.DATA, toSend);
                    } else {
                        msg = new Message(MessageType.DATA_SHORT, toSend);
                    }

                    /* ALOHA */

                    // The probability grows with the size of the sending queue
                    int q = 60 - Math.max(sendingQueue.size(), 10);

                    while(!sendingQueue.contains(msg)) {
                        if (new Random().nextInt(100) < q) {
                            sendingQueue.put(msg);
                            break;
                        }
                        Thread.sleep(1000); // 1 second time slot
                    }
                }
            }
        } catch (InterruptedException | IOException e){ System.exit(2); }
    }

    public static void main (String args[]){
            if (args.length > 0) {
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
                //            int length = Math.min(bytes.get(0), bytesLength);
                int length = bytes.get(0);
                System.out.println("Lungimea: " + length);
                System.out.print("[" +getCurrentTime() + "] ");
                for (int i = 1; i < length; i++) {
                    byte charByte = bytes.get(i);
                    System.out.print((char) charByte);
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
                            printByteBuffer(m.getData(), m.getData().capacity());
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

    // Prints current time in the "[HH:mm:ss]" pattern
    public static String getCurrentTime() {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");
        return currentTime.format(formatter);
    }
}


