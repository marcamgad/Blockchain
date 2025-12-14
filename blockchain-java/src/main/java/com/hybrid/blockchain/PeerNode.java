package com.hybrid.blockchain;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PeerNode {
    private final int port;
    private final Set<String> peers;
    private final ExecutorService executor;

    public PeerNode(int port){
        this.port = port;
        this.peers = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() throws IOException{
        ServerSocket serverSocket = new ServerSocket(this.port);
        executor.submit(() -> {
            while (true) {
                Socket client = serverSocket.accept();
                handleConnection(client);
            }
        });
    }

    private void handleConnection(Socket socket){
        executor.submit(() -> {
            try(ObjectInputStream in = new ObjectInputStream(socket.getInputStream())){
                Object msg = in.readObject();
                System.out.println("Received message: " + msg.getClass().getSimpleName());
            } catch(Exception e){
                e.printStackTrace();
            }
        });
    }

    public void connectToPeer(String host, int port){
        peers.add(host + ":" + port);
    }

    public void broadcast(Object message){
        for(String peer : peers){
            String[] parts = peer.split(":");
            try(Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())){
                     out.writeObject(message);
                } catch(Exception ignored){}
        }
    }

    public Set<String> getPeers(){
        return this.peers;
    }
}
