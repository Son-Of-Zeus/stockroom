try {
    rs.status();
} catch (error) {
    if (error.code === 94) {
        rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongo:27017'}]});
    } else {
        throw error;
    }
}
let ready = false;
for (let attempt = 0; attempt < 60; attempt++) {
    if (db.hello().isWritablePrimary) {
        ready = true;
        break;
    }
    sleep(1000);
}
if (!ready) throw new Error('MongoDB primary did not become ready.');
