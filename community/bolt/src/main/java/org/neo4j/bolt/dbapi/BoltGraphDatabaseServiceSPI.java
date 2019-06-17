/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.dbapi;

import java.time.Duration;
import java.util.Map;

import org.neo4j.bolt.runtime.AccessMode;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.KernelTransaction;

/**
 * A database representation as seen and used by Bolt.
 */
public interface BoltGraphDatabaseServiceSPI
{
    BoltTransaction beginTransaction( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo clientInfo, Duration txTimeout,
            AccessMode accessMode, Map<String,Object> txMetadata );

    /**
     * Returns a query executor that should be used for executing queries that use periodic commit.
     * <p>
     * This special executor must be used for queries with periodic commit, because such queries must not be executed
     * in an explicitly started transaction, {@link BoltTransaction} should be used for any other queries.
     */
    BoltQueryExecutor getPeriodicCommitExecutor( LoginContext loginContext, ClientConnectionInfo clientInfo, Duration txTimeout,
            AccessMode accessMode, Map<String,Object> txMetadata );

    boolean isPeriodicCommit( String query );

    void awaitUpToDate( long oldestAcceptableTxId, Duration timeout ) throws TransactionFailureException;

    long newestEncounteredTxId();

    String getDatabaseName();
}