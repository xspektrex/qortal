package org.qortal.controller;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFileChunk;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

public class ArbitraryDataManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	private static final long ARBITRARY_REQUEST_TIMEOUT = 5 * 1000L; // ms

	private static ArbitraryDataManager instance;

	private volatile boolean isStopping = false;


	/**
	 * Map of recent requests for ARBITRARY transaction data file lists.
	 * <p>
	 * Key is original request's message ID<br>
	 * Value is Triple&lt;transaction signature in base58, first requesting peer, first request's timestamp&gt;
	 * <p>
	 * If peer is null then either:<br>
	 * <ul>
	 * <li>we are the original requesting peer</li>
	 * <li>we have already sent data payload to original requesting peer.</li>
	 * </ul>
	 * If signature is null then we have already received the file list and either:<br>
	 * <ul>
	 * <li>we are the original requesting peer and have processed it</li>
	 * <li>we have forwarded the file list</li>
	 * </ul>
	 */
	public Map<Integer, Triple<String, Peer, Long>> arbitraryDataFileListRequests = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Map to keep track of in progress arbitrary data file requests
	 */
	private Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

	private ArbitraryDataManager() {
	}

	public static ArbitraryDataManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Manager");

		try {
			while (!isStopping) {
				Thread.sleep(2000);

				List<Peer> peers = Network.getInstance().getHandshakedPeers();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't fetch data if we don't have enough up-to-date peers
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					continue;
				}

				// Any arbitrary transactions we want to fetch data for?
				try (final Repository repository = RepositoryManager.getRepository()) {
					List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, null, ConfirmationStatus.BOTH, null, null, true);
					if (signatures == null || signatures.isEmpty()) {
						continue;
					}

					// Filter out those that already have local data
					signatures.removeIf(signature -> hasLocalData(repository, signature));

					if (signatures.isEmpty()) {
						continue;
					}

					// Pick one at random
					final int index = new Random().nextInt(signatures.size());
					byte[] signature = signatures.get(index);

					// Ask our connected peers if they have files for this signature
					// This process automatically then fetches the files themselves if a peer is found
					fetchArbitraryDataFileList(signature);

				} catch (DataException e) {
					LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
				}
			}
		} catch (InterruptedException e) {
			// Fall-through to exit thread...
		}
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

	private boolean hasLocalData(final Repository repository, final byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return true;

			ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);

			return arbitraryTransaction.isDataLocal();
		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's data is local", e);
			return true;
		}
	}

	private boolean fetchArbitraryDataFileList(byte[] signature) throws InterruptedException {
		LOGGER.info(String.format("Sending data file list request for signature %s", Base58.encode(signature)));
		// Build request
		Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature);

		// Save our request into requests map
		String signature58 = Base58.encode(signature);
		Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

		// Assign random ID to this message
		int id;
		do {
			id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

			// Put queue into map (keyed by message ID) so we can poll for a response
			// If putIfAbsent() doesn't return null, then this ID is already taken
		} while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
		getArbitraryDataFileListMessage.setId(id);

		// Broadcast request
		Network.getInstance().broadcast(peer -> getArbitraryDataFileListMessage);

		// Poll to see if data has arrived
		final long singleWait = 100;
		long totalWait = 0;
		while (totalWait < ARBITRARY_REQUEST_TIMEOUT) {
			Thread.sleep(singleWait);

			requestEntry = arbitraryDataFileListRequests.get(id);
			if (requestEntry == null)
				return false;

			if (requestEntry.getA() == null)
				break;

			totalWait += singleWait;
		}
		return true;
	}

	private ArbitraryDataFile fetchArbitraryDataFile(Peer peer, byte[] hash) throws InterruptedException {
		String hash58 = Base58.encode(hash);
		LOGGER.info(String.format("Fetching data file %.8s from peer %s", hash58, peer));
		arbitraryDataFileRequests.put(hash58, NTP.getTime());
		Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(hash);

		Message message = peer.getResponse(getArbitraryDataFileMessage);
		arbitraryDataFileRequests.remove(hash58);
		LOGGER.info(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

		if (message == null || message.getType() != Message.MessageType.ARBITRARY_DATA_FILE) {
			return null;
		}

		ArbitraryDataFileMessage arbitraryDataFileMessage = (ArbitraryDataFileMessage) message;
		return arbitraryDataFileMessage.getArbitraryDataFile();
	}

	public void cleanupRequestCache(long now) {
		final long requestMinimumTimestamp = now - ARBITRARY_REQUEST_TIMEOUT;
		arbitraryDataFileListRequests.entrySet().removeIf(entry -> entry.getValue().getC() < requestMinimumTimestamp);
		arbitraryDataFileRequests.entrySet().removeIf(entry -> entry.getValue() < requestMinimumTimestamp);
	}


	// Network handlers

	public void onNetworkGetArbitraryDataMessage(Peer peer, Message message) {
		GetArbitraryDataMessage getArbitraryDataMessage = (GetArbitraryDataMessage) message;

		byte[] signature = getArbitraryDataMessage.getSignature();
		String signature58 = Base58.encode(signature);
		Long timestamp = NTP.getTime();
		Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peer, timestamp);

		// If we've seen this request recently, then ignore
		if (arbitraryDataFileListRequests.putIfAbsent(message.getId(), newEntry) != null)
			return;

		// Do we even have this transaction?
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (transactionData == null || transactionData.getType() != TransactionType.ARBITRARY)
				return;

			ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);

			// If we have the data then send it
			if (transaction.isDataLocal()) {
				byte[] data = transaction.fetchData();
				if (data == null)
					return;

				// Update requests map to reflect that we've sent it
				newEntry = new Triple<>(signature58, null, timestamp);
				arbitraryDataFileListRequests.put(message.getId(), newEntry);

				Message arbitraryDataMessage = new ArbitraryDataMessage(signature, data);
				arbitraryDataMessage.setId(message.getId());
				if (!peer.sendMessage(arbitraryDataMessage))
					peer.disconnect("failed to send arbitrary data");

				return;
			}

			// Ask our other peers if they have it
			Network.getInstance().broadcast(broadcastPeer -> broadcastPeer == peer ? null : message);
		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while finding arbitrary transaction data for peer %s", peer), e);
		}
	}

	public void onNetworkArbitraryDataFileListMessage(Peer peer, Message message) {
		LOGGER.info("Received hash list from peer {}", peer);
		ArbitraryDataFileListMessage arbitraryDataFileListMessage = (ArbitraryDataFileListMessage) message;

		// Do we have a pending request for this data?
		Triple<String, Peer, Long> request = arbitraryDataFileListRequests.get(message.getId());
		if (request == null || request.getA() == null) {
			return;
		}

		// Does this message's signature match what we're expecting?
		byte[] signature = arbitraryDataFileListMessage.getSignature();
		String signature58 = Base58.encode(signature);
		if (!request.getA().equals(signature58)) {
			return;
		}

		List<byte[]> hashes = arbitraryDataFileListMessage.getHashes();
		if (hashes == null || hashes.isEmpty()) {
			return;
		}

		// Check transaction exists and hashes are correct
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return;

			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

			// Load data file(s)
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(arbitraryTransactionData.getData());
			arbitraryDataFile.addChunkHashes(arbitraryTransactionData.getChunkHashes());

			// Check all hashes exist
			for (byte[] hash : hashes) {
				//LOGGER.info("Received hash {}", Base58.encode(hash));
				if (!arbitraryDataFile.containsChunk(hash)) {
					LOGGER.info("Received non-matching chunk hash {} for signature {}", Base58.encode(hash), signature58);
					return;
				}
			}

			// Update requests map to reflect that we've received it
			Triple<String, Peer, Long> newEntry = new Triple<>(null, null, request.getC());
			arbitraryDataFileListRequests.put(message.getId(), newEntry);

			// Now fetch actual data from this peer
			for (byte[] hash : hashes) {
				if (!arbitraryDataFile.chunkExists(hash)) {
					// Only request the file if we aren't already requesting it from someone else
					if (!arbitraryDataFileRequests.containsKey(Base58.encode(hash))) {
						ArbitraryDataFile receivedArbitraryDataFile = fetchArbitraryDataFile(peer, hash);
						LOGGER.info("Received data file {} from peer {}", receivedArbitraryDataFile, peer);
					}
					else {
						LOGGER.info("Already requesting data file {}", arbitraryDataFile);
					}
				}
			}

		} catch (DataException | InterruptedException e) {
			LOGGER.error(String.format("Repository issue while finding arbitrary transaction data list for peer %s", peer), e);
		}

		// Forwarding (not yet used)
		Peer requestingPeer = request.getB();
		if (requestingPeer != null) {
			// Forward to requesting peer;
			if (!requestingPeer.sendMessage(arbitraryDataFileListMessage)) {
				requestingPeer.disconnect("failed to forward arbitrary data file list");
			}
		}
	}

	public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
		GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
		byte[] hash = getArbitraryDataFileMessage.getHash();
		Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash);
		if (arbitraryDataFile.exists()) {
			ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(arbitraryDataFile);
			arbitraryDataFileMessage.setId(message.getId());
			if (!peer.sendMessage(arbitraryDataFileMessage)) {
				LOGGER.info("Couldn't sent file");
				peer.disconnect("failed to send file");
			}
			LOGGER.info("Sent file {}", arbitraryDataFile);
		}
		else {

			// We don't have this file
			Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

			// Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
			LOGGER.debug(() -> String.format("Sending 'file unknown' response to peer %s for GET_FILE request for unknown file %s", peer, arbitraryDataFile));

			// We'll send empty block summaries message as it's very short
			// TODO: use a different message type here
			Message fileUnknownMessage = new BlockSummariesMessage(Collections.emptyList());
			fileUnknownMessage.setId(message.getId());
			if (!peer.sendMessage(fileUnknownMessage)) {
				LOGGER.info("Couldn't sent file-unknown response");
				peer.disconnect("failed to send file-unknown response");
			}
			LOGGER.info("Sent file-unknown response for file {}", arbitraryDataFile);
		}
	}

	public void onNetworkGetArbitraryDataFileListMessage(Peer peer, Message message) {
		GetArbitraryDataFileListMessage getArbitraryDataFileListMessage = (GetArbitraryDataFileListMessage) message;
		byte[] signature = getArbitraryDataFileListMessage.getSignature();
		Controller.getInstance().stats.getArbitraryDataFileListMessageStats.requests.incrementAndGet();

		LOGGER.info("Received hash list request from peer {} for signature {}", peer, Base58.encode(signature));

		List<byte[]> hashes = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {

			// Firstly we need to lookup this file on chain to get a list of its hashes
			ArbitraryTransactionData transactionData = (ArbitraryTransactionData)repository.getTransactionRepository().fromSignature(signature);
			if (transactionData instanceof ArbitraryTransactionData) {

				byte[] hash = transactionData.getData();
				byte[] chunkHashes = transactionData.getChunkHashes();

				// Load file(s) and add any that exist to the list of hashes
				ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash);
				if (chunkHashes != null && chunkHashes.length > 0) {
					arbitraryDataFile.addChunkHashes(chunkHashes);
					for (ArbitraryDataFileChunk chunk : arbitraryDataFile.getChunks()) {
						if (chunk.exists()) {
							hashes.add(chunk.getHash());
							//LOGGER.info("Added hash {}", chunk.getHash58());
						}
						else {
							LOGGER.info("Couldn't add hash {} because it doesn't exist", chunk.getHash58());
						}
					}
				}
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while fetching arbitrary file list for peer %s", peer), e);
		}

		ArbitraryDataFileListMessage arbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes);
		arbitraryDataFileListMessage.setId(message.getId());
		if (!peer.sendMessage(arbitraryDataFileListMessage)) {
			LOGGER.info("Couldn't send list of hashes");
			peer.disconnect("failed to send list of hashes");
		}
		LOGGER.info("Sent list of hashes", hashes);
	}

}
