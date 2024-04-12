import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5801;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    private static final byte src = (byte) new Random().nextInt(254);
    private static final byte MULTICAST = (byte) 255; //TODO: asta in int da 255, dar in byte da -1
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
            while (true) {
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
                    byte ack = 0;
                    byte seq = 0;
                    int offset=6; //pozitia din care incepe sa trimita pachetul
                    //asta imparte mesajul in packet-uri de 32 de bytes
                    while (read > 26) {
                        ByteBuffer toSend = ByteBuffer.allocate(32);
                        //TODO: implement sequence number and acknowledgment number
                        //create header and put in the buffer
                        ByteBuffer header = formatHeader(src, MULTICAST, seq, ack, 15, false, false, (byte) 26);
                        toSend.put(header.array());
                        // enter data without newline / returns
                        toSend.put(temp.array(), offset, 26); // 26 bytes de data
                        msg = new Message(MessageType.DATA, toSend);
                        sendingQueue.put(msg);
                        //TODO: de facut sa lucreze offsetul aici
                        offset+=26;
                        read-= 26;
                    }

                    // sends the last fragmented packet
                    ByteBuffer toSend = ByteBuffer.allocate(6 + read - new_line_offset);
                    ByteBuffer header = formatHeader(src, MULTICAST, (byte) 0, (byte) 0, 15, true, false, (byte) (read - new_line_offset));
                    toSend.put(header.array());
                    toSend.put(temp.array(), 0, read - new_line_offset);
                    if ((read - new_line_offset) > 2) {
                        msg = new Message(MessageType.DATA, toSend);
                    } else {
                        msg = new Message(MessageType.DATA_SHORT, toSend);
                    }

                    /* ALOHA */

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
        } catch (InterruptedException | IOException e){ System.exit(2); }
    }

    public static void main (String args[]) {
        if (args.length > 0) frequency = Integer.parseInt(args[0]);
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        //TODO: Make the nodes receive messages with the testing address equal to their address or 255
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            //            int length = Math.min(bytes.get(0), bytesLength);
            int length = bytes.get(5);
            System.out.println("Header values: ");
            for(int i = 0; i < 6; i++) {
                System.out.print(bytes.get(i) + " ");
            }
            System.out.println("\nLungimea: " + length);
            System.out.print("[" +getCurrentTime() + "] ");
            for (int i = 0; i < length; i++) {
                byte charByte = bytes.get(i+6);
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

    /**
     * Formats a header for a message packet.
     * @param source The source address
     * @param destination The destination address
     * @param seq The sequence number
     * @param ack The acknowledgment number
     * @param TTL The time to live
     * @param FIN The FIN flag
     * @param RMS The RMS flag
     * @param length The length of the packet
     * @return the packet with the header formatted.
     */
    private ByteBuffer formatHeader(byte source, byte destination, byte seq, byte ack, int TTL, boolean FIN, boolean RMS, byte length) {
        ByteBuffer packet = ByteBuffer.allocate(6);
        packet.put(source);
        packet.put(destination);
        packet.put(seq);
        packet.put(ack);
        // Format flags and TTL into a single byte
        packet.put((byte) ((TTL << 4) | (FIN ? 0b10 : 0) | (RMS ? 0b01 : 0)));
        packet.put(length);
        return packet;
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
}


