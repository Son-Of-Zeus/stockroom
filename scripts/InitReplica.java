import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import java.util.List;

class InitReplica {
    public static void main(String[] args) throws Exception {
        try (var client = MongoClients.create("mongodb://127.0.0.1:" + args[0] + "/?directConnection=true&serverSelectionTimeoutMS=10000")) {
            var admin = client.getDatabase("admin");
            try {
                admin.runCommand(new Document("replSetInitiate", new Document("_id", "rs0").append("members",
                        List.of(new Document("_id", 0).append("host", "127.0.0.1:" + args[0])))));
            } catch (MongoCommandException error) {
                if (error.getErrorCode() != 23) throw error;
            }
            for (int attempt = 0; attempt < 60; attempt++) {
                if (Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) return;
                Thread.sleep(500);
            }
            throw new IllegalStateException("MongoDB primary did not become ready.");
        }
    }
}
