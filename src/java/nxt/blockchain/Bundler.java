/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

package nxt.blockchain;

import nxt.Constants;
import nxt.Nxt;
import nxt.NxtException;
import nxt.account.Account;
import nxt.crypto.Crypto;
import nxt.db.DbIterator;
import nxt.db.FilteringIterator;
import nxt.util.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Bundler {

    private static final Map<ChildChain, Map<String, Bundler>> bundlers = new ConcurrentHashMap<>();
    private static final TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
    public static void init() {}

    static {
        transactionProcessor.addListener(transactions -> bundlers.values().forEach(chainBundlers -> chainBundlers.values().forEach(bundler -> {
            boolean hasChildChainTransactions = false;
            for (Transaction transaction : transactions) {
                if (transaction.getChain() == bundler.childChain) {
                    hasChildChainTransactions = true;
                    break;
                }
            }
            if (hasChildChainTransactions) {
                List<ChildBlockFxtTransaction> childBlockFxtTransactions = bundler.bundle();
                childBlockFxtTransactions.forEach(childBlockFxtTransaction -> {
                    try {
                        transactionProcessor.broadcast(childBlockFxtTransaction);
                    } catch (NxtException.ValidationException e) {
                        Logger.logErrorMessage(e.getMessage(), e);
                    }
                });
            }
        })), TransactionProcessor.Event.ADDED_UNCONFIRMED_TRANSACTIONS);
    }

    private final ChildChain childChain;
    private final String secretPhrase;
    private final byte[] publicKey;
    private final long accountId;
    private final long minRateNQTPerFXT;

    public Bundler(ChildChain childChain, String secretPhrase, long minRateNQTPerFXT) {
        this.childChain = childChain;
        this.secretPhrase = secretPhrase;
        this.publicKey = Crypto.getPublicKey(secretPhrase);
        this.accountId = Account.getId(publicKey);
        this.minRateNQTPerFXT = minRateNQTPerFXT;
        Map<String, Bundler> chainBundlers = bundlers.get(childChain);
        if (chainBundlers == null) {
            chainBundlers = new HashMap<>();
            bundlers.put(childChain, chainBundlers);
        }
        chainBundlers.put(secretPhrase, this);
    }

    public final ChildChain getChildChain() {
        return childChain;
    }

    public final byte[] getPublicKey() {
        return publicKey;
    }

    public final long getAccountId() {
        return accountId;
    }

    private List<ChildBlockFxtTransaction> bundle() {
        int blockchainHeight = Nxt.getBlockchain().getHeight();
        List<ChildBlockFxtTransaction> childBlockFxtTransactions = new ArrayList<>();
        try (FilteringIterator<UnconfirmedTransaction> unconfirmedTransactions = new FilteringIterator<>(
                TransactionProcessorImpl.getInstance().getUnconfirmedChildTransactions(childChain),
                transaction -> transaction.getTransaction().hasAllReferencedTransactions(transaction.getTimestamp(), 0))) {
            List<ChildTransaction> childTransactions = new ArrayList<>();
            Set<ChildTransaction> childTransactionSet = new HashSet<>();
            long totalMinFeeFQT = 0;
            long[] backFees = new long[3];
            while (unconfirmedTransactions.hasNext()) {
                ChildTransactionImpl childTransaction = (ChildTransactionImpl) unconfirmedTransactions.next().getTransaction();
                long minChildFeeFQT = childTransaction.getMinimumFeeFQT(blockchainHeight);
                long childFee = childTransaction.getFee();
                if (BigInteger.valueOf(childFee).multiply(BigInteger.valueOf(Constants.ONE_NXT))
                        .compareTo(BigInteger.valueOf(minRateNQTPerFXT).multiply(BigInteger.valueOf(minChildFeeFQT))) < 0) {
                    continue;
                }
                childTransactions.add(childTransaction);
                childTransactionSet.add(childTransaction);
                totalMinFeeFQT += minChildFeeFQT;
                if (minChildFeeFQT > 0) {
                    long[] childMinBackFees = childTransaction.getMinimumBackFeesFQT(blockchainHeight, minChildFeeFQT);
                    for (int i = 0; i < childMinBackFees.length; i++) {
                        backFees[i] += childMinBackFees[i];
                    }
                }
                //TODO: need to check block size limits in addition to transaction count
                if (childTransactions.size() == Constants.MAX_NUMBER_OF_TRANSACTIONS) {
                    if (!hasChildBlockFxtTransaction(childTransactionSet)) {
                        childBlockFxtTransactions.add(bundle(childTransactions, totalMinFeeFQT, backFees));
                    }
                    childTransactions = new ArrayList<>();
                    childTransactionSet.clear();
                    totalMinFeeFQT = 0;
                    backFees = new long[3];
                }
            }
        }
        return childBlockFxtTransactions;
    }

    private ChildBlockFxtTransaction bundle(List<ChildTransaction> childTransactions, long feeFQT, long[] backFees) {
        byte[][] childTransactionFullHashes = new byte[childTransactions.size()][];
        for (int i = 0; i < childTransactionFullHashes.length; i++) {
            childTransactionFullHashes[i] = childTransactions.get(i).getFullHash();
        }
        FxtTransaction.Builder builder = Nxt.newTransactionBuilder(publicKey, 0, feeFQT, (short)10,
                new ChildBlockAttachment(childChain, childTransactionFullHashes, backFees));
        try {
            ChildBlockFxtTransaction childBlockFxtTransaction = (ChildBlockFxtTransaction)builder.build(secretPhrase);
            childBlockFxtTransaction.validate();
            Logger.logDebugMessage("Created ChildBlockFxtTransaction: " + childBlockFxtTransaction.getJSONObject().toJSONString());
            return childBlockFxtTransaction;
        } catch (NxtException.ValidationException e) {
            Logger.logInfoMessage(e.getMessage(), e);
            return null;
        }
    }

    private boolean hasChildBlockFxtTransaction(Set<ChildTransaction> childTransactions) {
        try (DbIterator<UnconfirmedTransaction> unconfirmedTransactions = transactionProcessor.getUnconfirmedFxtTransactions()) {
            while (unconfirmedTransactions.hasNext()) {
                FxtTransaction fxtTransaction = (FxtTransaction)unconfirmedTransactions.next().getTransaction();
                if (fxtTransaction.getType() == ChildBlockFxtTransactionType.INSTANCE && fxtTransaction.getSenderId() == accountId) {
                    try {
                        fxtTransaction.validate();
                    } catch (NxtException.ValidationException e) {
                        continue; // skip not currently valid
                    }
                    List<? extends ChildTransaction> bundledChildTransactions = fxtTransaction.getChildTransactions();
                    if (childTransactions.size() == bundledChildTransactions.size() && childTransactions.containsAll(bundledChildTransactions)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
