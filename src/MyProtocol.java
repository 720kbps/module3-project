import client.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    // Source address from 0 to 254
    // Source address 255 or byte -1 is used for broadcast to all nodes (neighboring)
    private static final byte src = (byte) new Random().nextInt(255);

    private static String SRCusername = ""; // Username of the source from above

    private boolean freeLink = true; // Keeps the link state ~ for CA

    // All the connections are stored here
    private List<RoutingInfo> routingTable = new ArrayList<>();

    private List<IncomingInfo> incomingBuffer = new ArrayList<>();
    private int receivedAck = 0;
    private int sentSeq = 255;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency)
            throws InterruptedException {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);
        new receiveThread (receivedQueue).start();

        routingMessage.start(); // Routing message thread
        clearRoutingTable.start(); // Routing update thread

        Thread.sleep(1000); // wait 1 second for framework
        chatInit(); // Chat initiation

        // TODO: Implement input mechanic for chat

        try {
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read;
            while(true) {
                read = System.in.read(temp.array());
                if (read < 1024) sendPackets(read, temp);
                else System.out.println("Character limit 1024 exceeded");
            }
        } catch(InterruptedException | IOException e) { System.exit(2); }
    }

    private void chatInit() {
        System.out.println("⁶\uD80C\uDD53");
        System.out.println("Your source address is: " + src + "\nChoose a username please");
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter username: ");
        String username = scanner.nextLine();
        while(!isValidUsername(username)) {
            System.out.print("Invalid username(>25)\nEnter new username: ");
            username = scanner.nextLine();
        }
        SRCusername = username;
        routingTable.add(new RoutingInfo(SRCusername, src, src));
        initRoutingMessage();
    }

    public static String getCurrentTime() {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");
        return currentTime.format(formatter);
    }

    public static void main (String args[]) throws InterruptedException {
        if (args.length > 0) frequency = Integer.parseInt(args[0]);
        new MyProtocol (SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        // TODO: Adjust for use in chat - only use the bellow for debugging
        public void printByteBuffer(ByteBuffer bytes, int bytesLength) {
            System.out.print("[" + getCurrentTime() + "] ");
            for (int j = 0; j < 6; j++)
            {
                byte charByte = bytes.get(j);
                System.out.print((int) charByte + " ");
            }
            for (int i = 6; i < bytesLength; i++) {
                byte charByte = bytes.get(i);
                System.out.print((char) charByte + "");
            }
            System.out.println();
        }

        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY) {
                        System.out.println("BUSY");
                        freeLink = false;
                    } else if (m.getType() == MessageType.FREE) {
                        System.out.println("FREE");
                        freeLink = true;
                    } else if (m.getType() == MessageType.DATA) {
                        freeLink = true;
                        System.out.print("DATA: ");
                        printByteBuffer (m.getData(), m.getData().capacity());
                        if(m.getData().get(1) == -1) routingUpdate (m.getData());
                        receivePackets (m.getData());
                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        // System.out.print("DATA_SHORT: ");
                        // printByteBuffer(m.getData(), m.getData().capacity());
                        freeLink = false;
                    } else if (m.getType() == MessageType.DONE_SENDING) {
                        freeLink = true;
                        // System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO) {
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING) {
                        // System.out.println("SENDING");
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

    public void sendPackets(int read, ByteBuffer temp) throws InterruptedException {
        int new_line_offset = 0;
        if(read > 0) {
            if(temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') {
                new_line_offset = 1;
            }
            if(read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r')) {
                new_line_offset = 2;
            }
            Message msg;
            int position=0;
            int seq = 1 + new Random().nextInt(200);
            while(read > 26) {
                sentSeq = seq;
                seq++;
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder(toSend, src, (byte) 0, seq, 0,
                               0, false, false, false, false, 26);
                toSend.put(temp.array(), position, 26);
                System.out.println("ss" + sentSeq);
                msg = new Message(MessageType.DATA, toSend);
                stopAndWaitSend(msg);
                position += 26;
                read -= 26;
            }
            sentSeq = seq;
            seq++;
            ByteBuffer toSend = ByteBuffer.allocate(32);
            headerBuilder(toSend, src, (byte) 0, seq, 0,
                           0, false, true, false, false, read);
            toSend.put(temp.array(), position, read - new_line_offset);
            msg = new Message(MessageType.DATA, toSend);
            stopAndWaitSend(msg);
            sentSeq = seq;
            System.out.println("Sent");
        }
    }

    public void headerBuilder(ByteBuffer packet, byte source, byte destination, int seq, int ack,
                               int TTL, boolean FRW, boolean FIN, boolean RMS, boolean INIT, int length) {
        packet.put(source); // Source Address
        packet.put(destination); // Destination Address
        packet.put((byte) seq); // Sequence number
        packet.put((byte) ack); // Acknowledgment number
        packet.put((byte) ((TTL << 4) | (FRW ? 0b1000 : 0) | (INIT ? 0b100 : 0)
                | (FIN ? 0b10 : 0) | (RMS ? 0b01 : 0)));
        packet.put((byte) length); // Payload length
    }

    public void sendPacketsHelper (Message msg) throws InterruptedException {
        // CA (collision avoidance) implementation
        while(true) {
            if(freeLink) {
                sendingQueue.put(msg);
                Thread.sleep(new Random().nextInt(500) + 500); // Random back off
                break;
            }
            Thread.sleep(1000); // Time slots of 1 second
        }
    }

    public void initRoutingMessage() {
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        headerBuilder(routingMessage, src, (byte) 255, 0, 0, 0,
                      false, false, true, true, SRCusername.length());
        for (char c : SRCusername.toCharArray()) { routingMessage.put((byte) c); }
        Message msg = new Message(MessageType.DATA, routingMessage);
        try { sendPacketsHelper(msg); }
        catch (InterruptedException e) {}
    }

    Thread routingMessage = new Thread(() -> {
        while (true) {
            if (!Objects.equals(SRCusername, "")) {
                initRoutingMessage();
            }
            try {
                Thread.sleep(4000);
                Thread.sleep(new Random().nextInt(2000));
            } catch(InterruptedException e) { e.printStackTrace(); }
        }
    });

    Thread clearRoutingTable = new Thread(() -> {
        while(true) {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) { e.printStackTrace(); }
            routingTable.clear();
            routingTable.add(new RoutingInfo(SRCusername, src, src));
        }
    });

    public boolean isValidUsername (String username) { return username.length() <= 25; }

    public boolean isInRoutingTable (byte address) {
        for (RoutingInfo r : routingTable) {
            if (r.address == address) { return true; }
        } return false;
    }

    public void printRoutingTable () {
        for (RoutingInfo r : routingTable) {
            System.out.print(r.username + " ");
            System.out.print(r.address + " ");
            System.out.println(r.nextHopAddress);
        }
        System.out.println();
    }

    public void routingUpdate (ByteBuffer packet) {
        printRoutingTable();
        ByteBuffer routingMessage = ByteBuffer.allocate(32);
        String username = "";
        for (int i = 0; i < 6 + (int) packet.get(5); i++) {
            if (i > 5) username += (char) packet.get(i);
            if (i == 4)  routingMessage.put((byte) 1);
            else routingMessage.put(packet.get(i));
        }
        if (packet.get(4) == 5 && !isInRoutingTable (packet.get(0))) {
            routingTable.add(new RoutingInfo(username, packet.get(0), src));
            routingMessage.put(src);
            Message msg = new Message(MessageType.DATA, routingMessage);
            try { sendingQueue.put(msg); }
            catch (InterruptedException e) {}
        } else if (packet.get(4) == 1 && !isInRoutingTable (packet.get(0))) {
            int ss = (int) packet.get(5) + 6;
            routingTable.add(new RoutingInfo(username, packet.get(0), packet.get(ss)));
            Message msg = new Message(MessageType.DATA, packet);
            try { sendPacketsHelper(msg); }
            catch(InterruptedException e) { }
        }
    }

    public void receivePackets (ByteBuffer packet) throws InterruptedException {
        if(packet.get(1) != -1) {
            if(packet.get(3) == 0 && packet.get(2) != 0) {
                if(packet.get(4) == 2) {
                    int q = isInIncomingBuffer(packet.get(0), packet.get(2) & 0xFF);
                    if(q != -1) {
                        incomingBuffer.get(q).message =
                        buildMessage(incomingBuffer.get(q).message, packet);
                        incomingBuffer.get(q).fullMessageArrived = true;
                        printIncomingBuffer();
                    } else {
                        incomingBuffer.add(new IncomingInfo(getUsername(packet.get(0)), packet.get(0),
                                                            (packet.get(2) & 0xFF) + 1, "", false, true));
                        incomingBuffer.get(incomingBuffer.size() - 1).message =
                        buildMessage(incomingBuffer.get(incomingBuffer.size() - 1).message, packet);
                        printIncomingBuffer();
                    }
                } else if(packet.get(4) == 10) {

                } else if(packet.get(4) == 0) {
                    int q = isInIncomingBuffer(packet.get(0), packet.get(2) & 0xFF);
                    if(q != -1) {
                        incomingBuffer.get(q).message =
                                buildMessage(incomingBuffer.get(q).message, packet);
                        incomingBuffer.get(q).seq++;
                        printIncomingBuffer();
                    } else {
                        incomingBuffer.add(new IncomingInfo(getUsername(packet.get(0)), packet.get(0),
                                                            (packet.get(2) & 0xFF) + 1, "", false, false));
                        incomingBuffer.get(incomingBuffer.size() - 1).message =
                                buildMessage(incomingBuffer.get(incomingBuffer.size() - 1).message, packet);
                        printIncomingBuffer();
                    }
                }
                ByteBuffer ack = ByteBuffer.allocate(32);
                int tack = (packet.get(2) & 0xFF);
                headerBuilder(ack, src, packet.get(0), 0, tack, 0, false, true, false, false, 0);
                Message msg = new Message(MessageType.DATA, ack);
                sendPacketsHelper(msg);
            } else if(packet.get(3) != 0 && packet.get(2) == 0) {
                receivedAck = (packet.get(3) & 0xFF);
                System.out.println("RA" + receivedAck + " ss " + sentSeq);
            }
        }
    }

    public int isInIncomingBuffer(byte address, int seq) {
        for (int i = 0; i < incomingBuffer.size(); i++) {
            if(incomingBuffer.get(i).address == address && incomingBuffer.get(i).seq == seq)
            { return i; }
        } return -1;
    }

    public String buildMessage (String message, ByteBuffer packet) {
        StringBuilder messageBuilder = new StringBuilder(message);
        for(int i = 6; i < 6 + packet.get(5); i++) {
            messageBuilder.append((char) packet.get(i));
        }
        message = messageBuilder.toString();
        return message;
    }

    public String getUsername(byte address) {
        for (RoutingInfo r : routingTable) {
            if (r.address == address) return r.username;
        } return "Unknown";
    }

    public void printIncomingBuffer() {
        for(IncomingInfo i : incomingBuffer) {
            System.out.print(i.username + " ");
            System.out.print(i.address + " ");
            System.out.print(i.seq + " ");
            System.out.print(i.message + " ");
            System.out.print(i.toBeForwarded + " ");
            System.out.println(i.fullMessageArrived);
        }
        System.out.println();
    }

    public void stopAndWaitSend (Message msg) throws InterruptedException {
        int delay = 3000;
        while(true) {
            if(delay == 3000) {
                delay = 0;
                sendPacketsHelper(msg);
            }
            if(receivedAck == sentSeq + 1) break;
            Thread.sleep(100);
            delay += 100;
        }
    }

}


