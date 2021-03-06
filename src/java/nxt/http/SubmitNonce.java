package nxt.http;

import java.math.BigInteger;
import java.nio.ByteBuffer;

import javax.servlet.http.HttpServletRequest;

import nxt.Account;
import nxt.Block;
import nxt.Generator;
import nxt.Nxt;
import nxt.crypto.Crypto;
import nxt.util.Convert;
import fr.cryptohash.Shabal256;

import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;


public final class SubmitNonce extends APIServlet.APIRequestHandler {
    static final SubmitNonce instance = new SubmitNonce();

    private SubmitNonce() {
        super(new APITag[] {APITag.MINING}, "secretPhrase", "nonce", "accountId");
    }

    @Override
    protected JSONStreamAware processRequest(HttpServletRequest req) {
        String secret = req.getParameter("secretPhrase");
        Long nonce = Convert.parseUnsignedLong(req.getParameter("nonce"));

        String accountId = req.getParameter("accountId");

        JSONObject response = new JSONObject();

        if (secret == null) {
            response.put("result", "Missing secretPhrase");
            return response;
        }

        if (nonce == null) {
            response.put("result", "Missing nonce");
            return response;
        }

        byte[] secretPublicKey = Crypto.getPublicKey(secret);
        Account secretAccount = Account.getAccount(secretPublicKey);
        
        if (secretAccount != null) {
            Account genAccount;
            if (accountId != null) {
                genAccount = Account.getAccount(Convert.parseAccountId(accountId));
            } else {
                genAccount = secretAccount;
            }

            if (genAccount != null) {
                Account.RewardRecipientAssignment assignment = genAccount.getRewardRecipientAssignment();
                Long rewardId;
                
                if (assignment == null) {
                    rewardId = genAccount.getId();
                } else if (assignment.getFromHeight() > Nxt.getBlockchain().getHeight() + 1) {
                    rewardId = assignment.getPrevRecipientId();
                } else {
                    rewardId = assignment.getRecipientId();
                }
                
                if (rewardId != secretAccount.getId()) {
                    response.put("result", "Passphrase does not match reward recipient");
                    return response;
                }
            } else {
                response.put("result", "Passphrase is for a different account");
                return response;
            }
        }

        BigInteger POCTime = null;
        if (accountId == null || secretAccount == null) {
            POCTime = Generator.submitNonce(secret, nonce);
        } else {
            byte[] publicKey = Account.getPublicKey(Convert.parseUnsignedLong(accountId));
            if (publicKey == null) {
                response.put("result", "Passthrough mining requires public key in blockchain");
                return response;
            }
            POCTime = Generator.submitNonce(secret, nonce, publicKey);
        }

        if (POCTime == null) {
            response.put("result", "Failed to submit nonce to Generator");
            return response;
        }

        response.put("result", "success");
        response.put("deadline", POCTime);

        return response;
    }

    @Override
    protected boolean requirePost() {
        return true;
    }
}
