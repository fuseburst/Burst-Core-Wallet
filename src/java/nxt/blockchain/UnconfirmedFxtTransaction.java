/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
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

import nxt.NxtException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class UnconfirmedFxtTransaction extends UnconfirmedTransaction implements FxtTransaction {

    UnconfirmedFxtTransaction(FxtTransactionImpl transaction, long arrivalTimestamp) {
        super(transaction, arrivalTimestamp, true);
    }

    UnconfirmedFxtTransaction(ResultSet rs) throws SQLException, NxtException.NotValidException {
        super(TransactionImpl.newTransactionBuilder(rs.getBytes("transaction_bytes")), rs);
    }

    @Override
    public FxtTransactionImpl getTransaction() {
        return (FxtTransactionImpl)super.getTransaction();
    }

    @Override
    public List<? extends ChildTransaction> getChildTransactions() {
        return getTransaction().getChildTransactions();
    }

    @Override
    public List<? extends ChildTransaction> getSortedChildTransactions() {
        return getTransaction().getSortedChildTransactions();
    }

    @Override
    public void setChildTransactions(List<? extends ChildTransaction> childTransactions, byte[] blockHash) throws NxtException.NotValidException {
        getTransaction().setChildTransactions(childTransactions, blockHash);
    }

}