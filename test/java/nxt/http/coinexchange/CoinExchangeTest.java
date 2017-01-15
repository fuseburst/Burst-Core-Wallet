package nxt.http.coinexchange;

import nxt.BlockchainTest;
import nxt.blockchain.ChildChain;
import nxt.http.APICall;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import static nxt.blockchain.ChildChain.IGNIS;
import static nxt.blockchain.ChildChain.USD;

public class CoinExchangeTest extends BlockchainTest {

    @Test
    public void simpleExchange() {
        // Want to buy 25 USD with a maximum price of 4 IGNIS per USD
        // Convert the amount to IGNIS
        long displayUsdAmount = 25;
        long displayIgnisPerUsdPrice = 4;

        long nqtIgnisPerUsdPrice = displayIgnisPerUsdPrice * IGNIS.ONE_COIN;
        long nqtIgnisAmount = displayUsdAmount * nqtIgnisPerUsdPrice;

        // Submit request to buy 100 IGNIS worth of USD with a maximum price of 4 IGNIS per USD
        // Both amount and price are denominated in IGNIS
        APICall apiCall = new APICall.Builder("exchangeCoins").
                secretPhrase(ALICE.getSecretPhrase()).
                feeNQT(0).
                param("feeRateNQTPerFXT", IGNIS.ONE_COIN).
                param("chain", IGNIS.getId()).
                param("exchange", USD.getId()).
                param("amountNQT", nqtIgnisAmount).
                param("priceNQT", nqtIgnisPerUsdPrice).
                build();
        JSONObject response = apiCall.invoke();
        Logger.logDebugMessage("exchangeCoins: " + response);
        generateBlock();

        JSONObject transactionJSON = (JSONObject)response.get("transactionJSON");
        long orderId = Convert.fullHashToId(Convert.parseHexString((String)transactionJSON.get("fullHash")));
        apiCall = new APICall.Builder("getCoinExchangeOrder").
                param("order", Long.toUnsignedString(orderId)).
                build();
        response = apiCall.invoke();
        Assert.assertEquals(Long.toString(100 * IGNIS.ONE_COIN), response.get("amountNQT"));
        Assert.assertEquals(Long.toString(4 * IGNIS.ONE_COIN), response.get("bidNQT"));
        Assert.assertEquals(Long.toString((long)(1.0 / 4 * USD.ONE_COIN)), response.get("askNQT"));

        // Want to buy 25 USD worth of IGNIS with a maximum price of 1/4 USD per IGNIS
        // Both amount and price are denominated in USD
        apiCall = new APICall.Builder("exchangeCoins").
                secretPhrase(BOB.getSecretPhrase()).
                feeNQT(0).
                param("feeRateNQTPerFXT", USD.ONE_COIN).
                param("chain", USD.getId()).
                param("exchange", IGNIS.getId()).
                param("amountNQT", 25 * USD.ONE_COIN + 10 * USD.ONE_COIN).
                param("priceNQT", USD.ONE_COIN / 4).
                build();
        response = apiCall.invoke();
        Logger.logDebugMessage("exchangeCoins: " + response);
        generateBlock();

        transactionJSON = (JSONObject)response.get("transactionJSON");
        orderId = Convert.fullHashToId(Convert.parseHexString((String)transactionJSON.get("fullHash")));
        apiCall = new APICall.Builder("getCoinExchangeOrder").
                param("order", Long.toUnsignedString(orderId)).
                build();
        response = apiCall.invoke();
        Assert.assertEquals(Long.toString(10 * USD.ONE_COIN), response.get("amountNQT")); // leftover after the exchange of 25
        Assert.assertEquals(Long.toString((long) (0.25 * USD.ONE_COIN)), response.get("bidNQT"));
        Assert.assertEquals(Long.toString(4 * IGNIS.ONE_COIN), response.get("askNQT"));

        // Now look at the resulting trades
        apiCall = new APICall.Builder("getCoinExchangeTrades").
                param("chain", ChildChain.USD.getId()).
                param("account", BOB.getRsAccount()).
                build();
        response = apiCall.invoke();
        Logger.logDebugMessage("GetCoinExchangeTrades: " + response);

        // Bob received 100 IGNIS and paid 0.25 USD per IGNIS
        JSONArray trades = (JSONArray) response.get("trades");
        JSONObject trade = (JSONObject) trades.get(0);
        Assert.assertEquals(USD.getId(), (int)(long)trade.get("chain"));
        Assert.assertEquals(IGNIS.getId(), (int)(long)trade.get("exchange"));
        Assert.assertEquals("" + (100 * IGNIS.ONE_COIN), trade.get("amountNQT")); // IGNIS bought
        Assert.assertEquals("" + (long)(0.25 * USD.ONE_COIN), trade.get("priceNQT")); // USD per IGNIS price

        apiCall = new APICall.Builder("getCoinExchangeTrades").
                param("chain", IGNIS.getId()).
                param("account", ALICE.getRsAccount()).
                build();
        response = apiCall.invoke();
        Logger.logDebugMessage("GetCoinExchangeTrades: " + response);

        // Alice received 25 USD and paid 4 IGNIS per USD
        trades = (JSONArray) response.get("trades");
        trade = (JSONObject) trades.get(0);
        Assert.assertEquals(IGNIS.getId(), (int)(long)trade.get("chain"));
        Assert.assertEquals(USD.getId(), (int)(long)trade.get("exchange"));
        Assert.assertEquals("" + (25 * USD.ONE_COIN), trade.get("amountNQT")); // USD bought
        Assert.assertEquals("" + (4 * IGNIS.ONE_COIN), trade.get("priceNQT")); // IGNIS per USD price

        Assert.assertEquals(-100 * IGNIS.ONE_COIN - IGNIS.ONE_COIN / 10, ALICE.getChainBalanceDiff(IGNIS.getId()));
        Assert.assertEquals(25 * USD.ONE_COIN, ALICE.getChainBalanceDiff(USD.getId()));
        Assert.assertEquals(100 * IGNIS.ONE_COIN, BOB.getChainBalanceDiff(IGNIS.getId()));
        Assert.assertEquals(-25 * USD.ONE_COIN - USD.ONE_COIN / 10, BOB.getChainBalanceDiff(USD.getId()));
    }
}
