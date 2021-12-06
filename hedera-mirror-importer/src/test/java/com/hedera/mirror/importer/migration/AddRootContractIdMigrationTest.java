package com.hedera.mirror.importer.migration;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.test.context.TestPropertySource;

import com.hedera.mirror.importer.EnabledIfV1;
import com.hedera.mirror.importer.IntegrationTest;

@EnabledIfV1
@Tag("migration")
@TestPropertySource(properties = "spring.flyway.target=1.50.2")
class AddRootContractIdMigrationTest extends IntegrationTest {

    @Resource
    private JdbcOperations jdbcOperations;

    @Value("classpath:db/migration/v1/V1.50.3__contract_logs_root_id.sql")
    private File migrationSql;

    @Test
    void verifyRootContractIdMigration() throws Exception {

        persistContractResult(Arrays.asList(
                contractResult(1, Long.valueOf(1)),
                contractResult(2, Long.valueOf(2)),
                contractResult(3, null)
        ));
        persistContractLog(Arrays.asList(
                contractLog(1, 1, 0),
                contractLog(1, 2, 1),
                contractLog(1, 3, 2),
                contractLog(2, 1, 0),
                contractLog(3, 1, 0)
        ));
        // migration
        migrate();

        List<MigrationContractLog> results = retrieveContractLogs();
        assertThat(results.get(0).getRootContractId()).isEqualTo(1);
        assertThat(results.get(1).getRootContractId()).isEqualTo(1);
        assertThat(results.get(2).getRootContractId()).isEqualTo(1);
        assertThat(results.get(3).getRootContractId()).isEqualTo(2);
        assertThat(results.get(4).getRootContractId()).isNull();
    }

    private MigrationContractLog contractLog(long consensusTimestamp, long contractId, int index) {
        MigrationContractLog migrationContractLog = new MigrationContractLog();
        migrationContractLog.setConsensusTimestamp(consensusTimestamp);
        migrationContractLog.setContractId(contractId);
        migrationContractLog.setIndex(index);
        return migrationContractLog;
    }

    private MigrationContractResult contractResult(long consensusTimestamp, Long contractId) {
        MigrationContractResult migrationContractResult = new MigrationContractResult();
        migrationContractResult.setConsensusTimestamp(consensusTimestamp);
        migrationContractResult.setContractId(contractId);
        return migrationContractResult;
    }

    private void migrate() throws Exception {
        jdbcOperations.update(FileUtils.readFileToString(migrationSql, "UTF-8"));
    }

    private List<MigrationContractLog> retrieveContractLogs() {
        return jdbcOperations.query("select consensus_timestamp, contract_id, root_contract_id from contract_log " +
                        "order by root_contract_id asc",
                new BeanPropertyRowMapper<>(MigrationContractLog.class));
    }

    private void persistContractLog(List<MigrationContractLog> contractLogs) {
        for (MigrationContractLog contractLog : contractLogs) {
            jdbcOperations
                    .update("insert into contract_log (bloom, consensus_timestamp, contract_id, data, " +
                                    "index, " +
                                    "payer_account_id) " +
                                    " values" +
                                    " (?, ?, ?, ?, ?, ?)",
                            contractLog.getBloom(), contractLog.getConsensusTimestamp(), contractLog.getContractId(),
                            contractLog.getData(), contractLog.getIndex(), contractLog.getPayerAccountId());
        }
    }

    private void persistContractResult(List<MigrationContractResult> contractResults) {
        for (MigrationContractResult contractResult : contractResults) {
            jdbcOperations
                    .update("insert into contract_result (consensus_timestamp, contract_id, function_parameters, " +
                                    "gas_limit, gas_used, payer_account_id) " +
                                    " values" +
                                    " (?, ?, ?, ?, ?, ?)",
                            contractResult.getConsensusTimestamp(), contractResult.getContractId(),
                            contractResult.getFunctionParameters(),
                            contractResult.getGasLimit(), contractResult.getGasUsed(),
                            contractResult.getPayerAccountId());
        }
    }

    @Data
    @NoArgsConstructor
    private static class MigrationContractLog {
        private byte[] bloom = new byte[] {2, 2};
        private long consensusTimestamp;
        private long contractId;
        private byte[] data = new byte[] {2, 2};
        private int index = 0;
        private long payerAccountId = 100;
        private Long rootContractId;
    }

    @Data
    @NoArgsConstructor
    private static class MigrationContractResult {
        private long consensusTimestamp;
        private Long contractId;
        private byte[] functionParameters = new byte[] {2, 2};
        private long gasLimit = 2;
        private long gasUsed = 2;
        private long payerAccountId = 100;
    }
}
