-- RagVault binlog 복제 권한 부여
-- mysql-binlog-connector-java 가 binlog 스트림을 수신하려면
-- REPLICATION SLAVE + REPLICATION CLIENT 권한이 필요하다.
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'raguser'@'%';
FLUSH PRIVILEGES;
