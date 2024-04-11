package com.solacesystems.pubsub.sdkperf;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteSDKP extends Remote {

    void doRemoteShutdown() throws RemoteException;
    long getPubCount() throws RemoteException;
    long getSubCount() throws RemoteException;
}
