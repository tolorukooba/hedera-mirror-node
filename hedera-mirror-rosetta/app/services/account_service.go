/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2022 Hedera Hashgraph, LLC
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

package services

import (
	"context"

	"github.com/coinbase/rosetta-sdk-go/server"
	rTypes "github.com/coinbase/rosetta-sdk-go/types"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/domain/types"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/errors"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/interfaces"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/tools"
)

// AccountAPIService implements the server.AccountAPIServicer interface.
type AccountAPIService struct {
	*BaseService
	accountRepo interfaces.AccountRepository
}

// NewAccountAPIService creates a new instance of a AccountAPIService.
func NewAccountAPIService(base *BaseService, accountRepo interfaces.AccountRepository) server.AccountAPIServicer {
	return &AccountAPIService{
		BaseService: base,
		accountRepo: accountRepo,
	}
}

// AccountBalance implements the /account/balance endpoint.
func (a *AccountAPIService) AccountBalance(
	ctx context.Context,
	request *rTypes.AccountBalanceRequest,
) (*rTypes.AccountBalanceResponse, *rTypes.Error) {
	var block *types.Block
	var err *rTypes.Error

	account, err := types.AccountFromString(request.AccountIdentifier.Address)
	if err != nil {
		return nil, err
	}

	if request.BlockIdentifier != nil {
		block, err = a.RetrieveBlock(ctx, request.BlockIdentifier)
	} else {
		block, err = a.RetrieveLatest(ctx)
	}
	if err != nil {
		return nil, err
	}

	balances, err := a.accountRepo.RetrieveBalanceAtBlock(ctx, account.EncodedId, block.ConsensusEndNanos)
	if err != nil {
		return nil, err
	}

	return &rTypes.AccountBalanceResponse{
		BlockIdentifier: &rTypes.BlockIdentifier{
			Index: block.Index,
			Hash:  tools.SafeAddHexPrefix(block.Hash),
		},
		Balances: a.toRosettaBalances(balances),
	}, nil
}

func (a *AccountAPIService) AccountCoins(
	_ context.Context,
	_ *rTypes.AccountCoinsRequest,
) (*rTypes.AccountCoinsResponse, *rTypes.Error) {
	return nil, errors.ErrNotImplemented
}

func (a *AccountAPIService) toRosettaBalances(balances []types.Amount) []*rTypes.Amount {
	rosettaBalances := make([]*rTypes.Amount, 0, len(balances))
	for _, balance := range balances {
		rosettaBalances = append(rosettaBalances, balance.ToRosetta())
	}

	return rosettaBalances
}
