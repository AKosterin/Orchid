package org.torproject.jtor.circuits.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.torproject.jtor.TorException;
import org.torproject.jtor.circuits.Circuit;
import org.torproject.jtor.circuits.CircuitNode;
import org.torproject.jtor.circuits.Connection;
import org.torproject.jtor.circuits.OpenStreamResponse;
import org.torproject.jtor.circuits.Stream;
import org.torproject.jtor.circuits.cells.Cell;
import org.torproject.jtor.circuits.cells.RelayCell;
import org.torproject.jtor.data.IPv4Address;
import org.torproject.jtor.data.exitpolicy.ExitTarget;
import org.torproject.jtor.directory.Router;

/**
 * This class represents an established circuit through the Tor network.
 *
 */
public class CircuitImpl implements Circuit {
	private final static Logger logger = Logger.getLogger(CircuitImpl.class.getName());
	
	static CircuitImpl create(CircuitManagerImpl circuitManager) {
		return new CircuitImpl(circuitManager, false);
	}
	
	static CircuitImpl createDirectoryCircuit(CircuitManagerImpl circuitManager) {
		return new CircuitImpl(circuitManager, true);
	}

	private final CircuitManagerImpl circuitManager;
	private final List<CircuitNodeImpl> nodeList;
	private final Set<ExitTarget> failedExitRequests;
	private final CircuitStatus status;
	private final boolean isDirectoryCircuit;

	private CircuitIO io;

	private CircuitImpl(CircuitManagerImpl circuitManager, boolean isDirectoryCircuit) {
		nodeList = new ArrayList<CircuitNodeImpl>();
		this.circuitManager = circuitManager;
		this.isDirectoryCircuit = isDirectoryCircuit;
		this.failedExitRequests = new HashSet<ExitTarget>();
		status = new CircuitStatus();
	}

	void bindToConnection(Connection connection) {
		if(io != null) {
			throw new IllegalStateException("Circuit already bound to a connection");
		}
		int id = connection.allocateCircuitId(this);
		io = new CircuitIO(this, connection, id);
	}

	void markForClose() {
		if(io != null) {
			io.markForClose();
		}
	}

	boolean isMarkedForClose() {
		if(io == null) {
			return false;
		} else {
			return io.isMarkedForClose();
		}
	}

	boolean isDirectoryCircuit() {
		return isDirectoryCircuit;
	}

	CircuitStatus getStatus() {
		return status;
	}
	
	public boolean isConnected() {
		return status.isConnected();
	}

	boolean isPending() {
		return status.isBuilding();
	}
	
	boolean isClean() {
		return status.isConnected() && !status.isDirty();
	}
	
	int getSecondsDirty() {
		return (int) (status.getMillisecondsDirty() / 1000);
	}

	void notifyCircuitBuildStart(CircuitCreationRequest request) {
		if(!status.isUnconnected()) {
			throw new IllegalStateException("Can only connect UNCONNECTED circuits");
		}
		status.updateCreatedTimestamp();
		status.setStateBuilding(request.getPath());
		circuitManager.addActiveCircuit(this);
	}
	
	void notifyCircuitBuildFailed() {
		status.setStateFailed();
		circuitManager.removeActiveCircuit(this);
	}
	
	void notifyCircuitBuildCompleted() {
		status.setStateOpen();
		status.updateCreatedTimestamp();
	}
	
	public Connection getConnection() {
		if(!isConnected())
			throw new TorException("Circuit is not connected.");
		return io.getConnection();
	}

	public int getCircuitId() {
		return io.getCircuitId();
	}

	public void sendRelayCell(RelayCell cell) {
		io.sendRelayCellTo(cell, cell.getCircuitNode());
	}

	public void sendRelayCellToFinalNode(RelayCell cell) {
		io.sendRelayCellTo(cell, getFinalCircuitNode());
	}

	void appendNode(CircuitNodeImpl node) {
		nodeList.add(node);
	}

	List<CircuitNodeImpl> getNodeList() {
		return nodeList;
	}

	int getCircuitLength() {
		return nodeList.size();
	}

	public CircuitNodeImpl getFinalCircuitNode() {
		if(nodeList.isEmpty())
			throw new TorException("getFinalCircuitNode() called on empty circuit");
		return nodeList.get( getCircuitLength() - 1);
	}

	public RelayCell createRelayCell(int relayCommand, int streamId, CircuitNode targetNode) {
		return io.createRelayCell(relayCommand, streamId, targetNode);
	}

	public RelayCell receiveRelayCell() {
		return io.dequeueRelayResponseCell();
	}

	void sendCell(Cell cell) {
		io.sendCell(cell);
	}
	
	Cell receiveControlCellResponse() {
		return io.receiveControlCellResponse();
	}

	/*
	 * This is called by the cell reading thread in ConnectionImpl to deliver control cells 
	 * associated with this circuit (CREATED or CREATED_FAST).
	 */
	public void deliverControlCell(Cell cell) {
		io.deliverControlCell(cell);
	}

	/* This is called by the cell reading thread in ConnectionImpl to deliver RELAY cells. */
	public void deliverRelayCell(Cell cell) {
		io.deliverRelayCell(cell);
	}

	public OpenStreamResponse openDirectoryStream() {
		final StreamImpl stream = io.createNewStream();
		final OpenStreamResponse response = stream.openDirectory();
		processOpenStreamResponse(stream, response);
		return response;
	}

	public OpenStreamResponse openExitStream(IPv4Address address, int port) {
		return openExitStream(address.toString(), port);
	}

	public OpenStreamResponse openExitStream(String target, int port) {
		final StreamImpl stream = io.createNewStream();
		final OpenStreamResponse response = stream.openExit(target, port);
		processOpenStreamResponse(stream, response);
		return response;
	}

	private void processOpenStreamResponse(StreamImpl stream, OpenStreamResponse response) {
		switch(response.getStatus()) {
		case STATUS_STREAM_TIMEOUT:
			logger.info("Timeout opening stream: "+ stream);
			if(status.countStreamTimeout()) {
				// XXX do something
			}
			removeStream(stream);
			break;

		case STATUS_STREAM_ERROR:
			logger.info("Error opening stream: "+ stream +" reason: "+ response.getErrorCodeMessage());
			removeStream(stream);
			break;
		case STATUS_STREAM_OPENED:
			break;
		}
	}

	boolean isFinalNodeDirectory() {
		return getFinalCircuitNode().getRouter().getDirectoryPort() != 0;
	}

	void setStateDestroyed() {
		status.setStateDestroyed();
		circuitManager.removeActiveCircuit(this);
	}

	public void destroyCircuit() {
		io.destroyCircuit();
		circuitManager.removeActiveCircuit(this);
	}


	void removeStream(StreamImpl stream) {
		io.removeStream(stream);
	}

	public void recordFailedExitTarget(ExitTarget target) {
		synchronized(failedExitRequests) {
			failedExitRequests.add(target);
		}
	}

	public boolean canHandleExitTo(ExitTarget target) {
		synchronized(failedExitRequests) {
			if(failedExitRequests.contains(target))
				return false;
		}

		final Router lastRouter = status.getFinalRouter();
		if(target.isAddressTarget())
			return lastRouter.exitPolicyAccepts(target.getAddress(), target.getPort());
		else
			return lastRouter.exitPolicyAccepts(target.getPort());
	}
	
	public boolean canHandleExitToPort(int port) {
		final Router lastRouter = status.getFinalRouter();
		return lastRouter.exitPolicyAccepts(port);
	}

	public String toString() {
		int id = (io == null) ? 0 : io.getCircuitId();
		if(status.isDirty()) {
			
		}
		return "  Circuit id="+ id +" state=" + status.getStateAsString() +" "+ pathToString();
	}

	
	private  String pathToString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("[");
		for(CircuitNode node: nodeList) {
			if(sb.length() > 1)
				sb.append(",");
			sb.append(node.toString());
		}
		sb.append("]");
		return sb.toString();
	}

	public List<Stream> getActiveStreams() {
		if(io == null) {
			return Collections.emptyList();
		} else {
			return io.getActiveStreams();
		}
	}

	public void dashboardRender(PrintWriter writer, int flags) throws IOException {
		writer.println(toString());
		if(io != null) {
			io.dashboardRender(writer, flags);
		}
	}
}
