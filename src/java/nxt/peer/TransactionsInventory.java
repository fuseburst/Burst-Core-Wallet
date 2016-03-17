/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package nxt.peer;

import nxt.Nxt;
import nxt.NxtException;
import nxt.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class TransactionsInventory {

    /** Transaction cache */
    private static final ConcurrentHashMap<Long, Transaction> transactionCache = new ConcurrentHashMap<>();

    /** Pending transactions */
    private static final Set<Long> pendingTransactions = Collections.synchronizedSet(new HashSet<>());

    private TransactionsInventory() {}

    /**
     * Process a TransactionsInventory message (there is no response message)
     *
     * @param   peer                    Peer
     * @param   request                 Request message
     * @return                          Response message
     */
    static NetworkMessage processRequest(PeerImpl peer, NetworkMessage.TransactionsInventoryMessage request) {
        List<Long> transactionIds = request.getTransactionIds();
        //
        // Request transactions that are not already in our cache
        //
        List<Long> requestIds = new ArrayList<>(Math.min(100, transactionIds.size()));
        for (Long transactionId : transactionIds) {
            if (transactionCache.get(transactionId) == null && !pendingTransactions.contains(transactionId)) {
                requestIds.add(transactionId);
                pendingTransactions.add(transactionId);
                if (requestIds.size() >= 100) {
                    break;
                }
            }
        }
        if (requestIds.isEmpty()) {
            return null;
        }
        //
        // Remove old transactions from our cache.  The transaction processor tracks
        // unconfirmed transactions, so our cache is just to reduce transaction
        // requests.
        //
        int now = Nxt.getEpochTime();
        Iterator<Transaction> it = transactionCache.values().iterator();
        while (it.hasNext()) {
            Transaction transaction = it.next();
            if (now - transaction.getTimestamp() > 10*60) {
                it.remove();
            }
        }
        Peers.peersService.execute(() -> {
            //
            // Request the transactions, starting with the peer that sent the TransactionsInventory
            // message.  We will update the transaction cache with transactions that have
            // been successfully processed.  We will keep contacting peers until
            // we have received all of the transactions or we run out of peers.
            //
            try {
                NetworkMessage.GetTransactionsMessage transactionsRequest =
                        new NetworkMessage.GetTransactionsMessage(requestIds);
                List<Peer> connectedPeers = Peers.getConnectedPeers();
                if (connectedPeers.isEmpty()) {
                        return;
                }
                int startIndex = connectedPeers.indexOf(peer);
                if (startIndex < 0) {
                    startIndex = 0;
                }
                int index = startIndex;
                while (true) {
                    Peer feederPeer = connectedPeers.get(index);
                    NetworkMessage.TransactionsMessage response =
                            (NetworkMessage.TransactionsMessage)feederPeer.sendRequest(transactionsRequest);
                    if (response != null && response.getTransactionCount() > 0) {
                        try {
                            List<Transaction> transactions = response.getTransactions();
                            Nxt.getTransactionProcessor().processPeerTransactions(transactions);
                            transactions.forEach(tx -> {
                                requestIds.remove(tx.getId());
                                pendingTransactions.remove(tx.getId());
                                transactionCache.put(tx.getId(), tx);
                            });
                        } catch (RuntimeException | NxtException.ValidationException e) {
                            feederPeer.blacklist(e);
                        }
                    }
                    if (requestIds.isEmpty()) {
                        break;
                    }
                    index = (index < connectedPeers.size()-1 ? index + 1 : 0);
                    if (index == startIndex) {
                        break;
                    }
                }
            } finally {
                requestIds.forEach(id -> pendingTransactions.remove(id));
            }
        });
        return null;
    }

    /**
     * Get a cached transaction
     *
     * @param   transactionId           The transaction identifier
     * @return                          Cached transaction or null
     */
    static Transaction getCachedTransaction(long transactionId) {
        return transactionCache.get(transactionId);
    }
}
