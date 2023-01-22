package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TransactionDAOTest {

    @Test
    public void testCacheAll() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(5));
        final String groupId = RandomData.genAlpha(4);
        final Transaction tx = new Transaction(userId, groupId, Transaction.Type.Shared);
        final Transaction tx2 = new Transaction(userId, groupId, Transaction.Type.Shared);
        final List<Transaction> txs = new ArrayList<>();
        txs.add(tx);
        txs.add(tx2);
        final Persistence persistence = new Persistence() {};
        final TransactionDAO dao = new TransactionDAO(new ObjectMapper(), persistence);
        final Map<String, Transaction> userTxs = dao.getTxCacheForUser(userId).join();
        assertEquals(userTxs.size(), 0, "Expected cache to start at 0.");
        persistence.cacheAll(userTxs, txs, Transaction::getTxId);
        assertEquals(userTxs.size(), 2, "Expected cache to add 2 items!");

        final Map<String, Transaction> verifySave = dao.getTxCacheForUser(userId).join();
        assertEquals(verifySave.size(), 2, "Expected cache to start at 2 this time!");
        persistence.cacheAll(verifySave, txs, Transaction::getTxId);
        assertEquals(verifySave.size(), 2, "Expected cache to add 2 items!");
    }
}