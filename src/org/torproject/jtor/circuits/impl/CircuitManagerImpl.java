package org.torproject.jtor.circuits.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.torproject.jtor.circuits.Circuit;
import org.torproject.jtor.circuits.CircuitBuildHandler;
import org.torproject.jtor.circuits.CircuitManager;
import org.torproject.jtor.circuits.CircuitNode;
import org.torproject.jtor.circuits.Connection;
import org.torproject.jtor.circuits.DirectoryStreamRequest;
import org.torproject.jtor.circuits.OpenStreamResponse;
import org.torproject.jtor.circuits.OpenStreamResponse.OpenStreamStatus;
import org.torproject.jtor.connections.ConnectionCache;
import org.torproject.jtor.crypto.TorRandom;
import org.torproject.jtor.data.IPv4Address;
import org.torproject.jtor.directory.Directory;

public class CircuitManagerImpl implements CircuitManager {
	private final static Logger logger = Logger.getLogger(CircuitManagerImpl.class.getName());
	private final static boolean DEBUG_CIRCUIT_CREATION = true;

	private final ConnectionCache connectionCache;
	private final Set<Circuit> pendingCircuits;
	private final Set<Circuit> activeCircuits;
	private final Set<Circuit> cleanCircuits;
	private final TorRandom random;
	private final List<StreamExitRequest> pendingExitStreams = new LinkedList<StreamExitRequest>();
	private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
	private final Runnable circuitCreationTask;
	private final TorInitializationTracker initializationTracker;

	public CircuitManagerImpl(Directory directory, ConnectionCache connectionCache, TorInitializationTracker initializationTracker) {
		this.connectionCache = connectionCache;
		this.circuitCreationTask = new CircuitCreationTask(directory, this);
		this.activeCircuits = new HashSet<Circuit>();
		this.pendingCircuits = new HashSet<Circuit>();
		this.cleanCircuits = new HashSet<Circuit>();
		this.random = new TorRandom();
		this.initializationTracker = initializationTracker;
	}

	
	public void notifyInitializationEvent(int eventCode) {
		initializationTracker.notifyEvent(eventCode);
	}

	public void startBuildingCircuits() {
		scheduledExecutor.scheduleAtFixedRate(circuitCreationTask, 0, 1000, TimeUnit.MILLISECONDS);

		if(DEBUG_CIRCUIT_CREATION) {
			Runnable debugTask = createCircuitCreationDebugTask();
			scheduledExecutor.scheduleAtFixedRate(debugTask, 0, 30000, TimeUnit.MILLISECONDS);
		}
	}

	private Runnable createCircuitCreationDebugTask() {
		return new Runnable() { public void run() {
			logger.fine("CLEAN: "+ getCleanCircuitCount() 
					+ " PENDING: "+ getPendingCircuitCount()
					+ " ACTIVE: "+ getActiveCircuitCount());
		}};
	}

	public Circuit createNewCircuit(boolean isDirectoryCircuit) {
		return CircuitImpl.create(this, connectionCache, isDirectoryCircuit, initializationTracker);
	}

	synchronized void circuitStartConnect(Circuit circuit) {
		pendingCircuits.add(circuit);
	}

	synchronized void circuitConnected(Circuit circuit) {
		pendingCircuits.remove(circuit);
		activeCircuits.add(circuit);
		cleanCircuits.add(circuit);
	}

	synchronized void circuitDirty(Circuit circuit) {
		cleanCircuits.remove(circuit);
	}

	synchronized void circuitInactive(Circuit circuit) {
		pendingCircuits.remove(circuit);
		activeCircuits.remove(circuit);
		cleanCircuits.remove(circuit);
	}

	synchronized int getCleanCircuitCount() {
		return cleanCircuits.size();
	}

	synchronized int getActiveCircuitCount() {
		return activeCircuits.size();
	}

	synchronized int getPendingCircuitCount() {
		return pendingCircuits.size();
	}

	List<Circuit> getRandomlyOrderedListOfActiveCircuits() {
		final ArrayList<Circuit> ac = new ArrayList<Circuit>(activeCircuits);
		final int sz = ac.size();
		for(int i = 0; i < sz; i++) {
			final Circuit tmp = ac.get(i);
			final int swapIdx = random.nextInt(sz);
			ac.set(i, ac.get(swapIdx));
			ac.set(swapIdx, tmp);
		}
		return ac;
	}

	public OpenStreamResponse openExitStreamTo(String hostname, int port)
			throws InterruptedException {
		return openExitStreamByRequest(new StreamExitRequest(this, hostname, port));
	}

	public OpenStreamResponse openExitStreamTo(IPv4Address address, int port)
			throws InterruptedException {
		return openExitStreamByRequest(new StreamExitRequest(this, address, port));
	}
	
	private OpenStreamResponse openExitStreamByRequest(StreamExitRequest request) throws InterruptedException {
		synchronized(pendingExitStreams) {
			pendingExitStreams.add(request);
			while(!request.isCompleted())
				pendingExitStreams.wait();
		}
		return request.getResponse();
	}
	
	List<StreamExitRequest> getPendingExitStreams() {
		synchronized(pendingExitStreams) {
			return new ArrayList<StreamExitRequest>(pendingExitStreams);
		}
	}
	
	List<Circuit> getPendingCircuits() {
		synchronized(pendingCircuits) {
			return new ArrayList<Circuit>(pendingCircuits);
		}
	}
	
	void streamRequestIsCompleted(StreamExitRequest request) {
		synchronized(pendingExitStreams) {
			pendingExitStreams.remove(request);
			pendingExitStreams.notifyAll();
		}
	}

	public OpenStreamResponse openDirectoryStream(DirectoryStreamRequest request) {
		final Circuit circuit = createNewCircuit(true);
		final boolean success = circuit.openCircuit(Arrays.asList(request.getDirectoryRouter()), new CircuitBuildHandler() {
			
			public void nodeAdded(CircuitNode node) {
				// TODO Auto-generated method stub
				
			}
			
			public void connectionFailed(String reason) {
				logger.fine("Connection failed: "+ reason);
				
			}
			
			public void connectionCompleted(Connection connection) {
				// TODO Auto-generated method stub
				
			}
			
			public void circuitBuildFailed(String reason) {
				logger.fine("Circuit Build Failed: "+ reason);
				// TODO Auto-generated method stub
				
			}
			
			public void circuitBuildCompleted(Circuit circuit) {
				// TODO Auto-generated method stub
				
			}
		}, true);
		if(success) {
			if(request.getRequestEventCode() > 0) {
				initializationTracker.notifyEvent(request.getRequestEventCode());
			}
			final OpenStreamResponse osr =  circuit.openDirectoryStream();
			if(osr.getStatus() == OpenStreamStatus.STATUS_STREAM_OPENED && request.getLoadingEventCode() > 0) {
				initializationTracker.notifyEvent(request.getLoadingEventCode());
			}
			return osr;
		} else {
			return OpenStreamResponseImpl.createConnectionFailError("Failed to open circuit"); 
		}
	}
}
