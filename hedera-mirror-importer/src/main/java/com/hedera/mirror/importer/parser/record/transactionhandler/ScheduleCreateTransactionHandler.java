package com.hedera.mirror.importer.parser.record.transactionhandler;

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

import javax.inject.Named;

import com.hedera.mirror.common.domain.entity.Entity;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.transaction.TransactionType;
import com.hedera.mirror.common.domain.transaction.RecordItem;
import com.hedera.mirror.importer.parser.record.entity.EntityListener;

@Named
class ScheduleCreateTransactionHandler extends AbstractEntityCrudTransactionHandler<Entity> {

    ScheduleCreateTransactionHandler(EntityListener entityListener) {
        super(entityListener, TransactionType.SCHEDULECREATE);
    }

    @Override
    public EntityId getEntity(RecordItem recordItem) {
        return EntityId.of(recordItem.getRecord().getReceipt().getScheduleID());
    }

    @Override
    protected void doUpdateEntity(Entity entity, RecordItem recordItem) {
        var transactionBody = recordItem.getTransactionBody().getScheduleCreate();

        if (transactionBody.hasAdminKey()) {
            entity.setKey(transactionBody.getAdminKey().toByteArray());
        }

        entity.setMemo(transactionBody.getMemo());
        entityListener.onEntity(entity);
    }
}
