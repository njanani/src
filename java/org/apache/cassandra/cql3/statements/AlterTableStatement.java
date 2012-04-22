/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.io.IOException;
import java.util.*;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.config.*;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.ColumnDef;
import org.apache.cassandra.thrift.InvalidRequestException;
import static org.apache.cassandra.thrift.ThriftValidation.validateColumnFamily;

public class AlterTableStatement extends SchemaAlteringStatement
{
    public static enum Type
    {
        ADD, ALTER, DROP, OPTS
    }

    public final Type oType;
    public final String validator;
    public final ColumnIdentifier columnName;
    private final CFPropDefs cfProps = new CFPropDefs();

    public AlterTableStatement(CFName name, Type type, ColumnIdentifier columnName, String validator, Map<String, String> propertyMap)
    {
        super(name);
        this.oType = type;
        this.columnName = columnName;
        this.validator = validator; // used only for ADD/ALTER commands
        this.cfProps.addAll(propertyMap);
    }

    public void announceMigration() throws InvalidRequestException, ConfigurationException
    {
        CFMetaData meta = validateColumnFamily(keyspace(), columnFamily());
        CFMetaData cfm = meta.clone();

        CFDefinition cfDef = meta.getCfDef();
        CFDefinition.Name name = this.oType == Type.OPTS ? null : cfDef.get(columnName);
        switch (oType)
        {
            case ADD:
                if (cfDef.isCompact)
                    throw new InvalidRequestException("Cannot add new column to a compact CF");
                if (name != null)
                {
                    switch (name.kind)
                    {
                        case KEY_ALIAS:
                        case COLUMN_ALIAS:
                            throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with a PRIMARY KEY part", columnName));
                        case COLUMN_METADATA:
                            throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with an existing column", columnName));
                    }
                }
                cfm.addColumnDefinition(new ColumnDefinition(columnName.key,
                                                             CFPropDefs.parseType(validator),
                                                             null,
                                                             null,
                                                             null,
                                                             cfDef.isComposite ? ((CompositeType)meta.comparator).types.size() - 1 : null));
                break;

            case ALTER:
                if (name == null)
                    throw new InvalidRequestException(String.format("Column %s was not found in CF %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                        cfm.keyValidator(CFPropDefs.parseType(validator));
                        break;
                    case COLUMN_ALIAS:
                        throw new InvalidRequestException(String.format("Cannot alter PRIMARY KEY part %s", columnName));
                    case VALUE_ALIAS:
                        cfm.defaultValidator(CFPropDefs.parseType(validator));
                        break;
                    case COLUMN_METADATA:
                        ColumnDefinition column = meta.getColumnDefinition(columnName.key);
                        column.setValidator(CFPropDefs.parseType(validator));
                        cfm.addColumnDefinition(column);
                        break;
                }
                break;

            case DROP:
                if (cfDef.isCompact)
                    throw new InvalidRequestException("Cannot drop columns from a compact CF");
                if (name == null)
                    throw new InvalidRequestException(String.format("Column %s was not found in CF %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                    case COLUMN_ALIAS:
                        throw new InvalidRequestException(String.format("Cannot drop PRIMARY KEY part %s", columnName));
                    case COLUMN_METADATA:
                        ColumnDefinition toDelete = null;
                        for (ColumnDefinition columnDef : cfm.getColumn_metadata().values())
                        {
                            if (columnDef.name.equals(columnName.key))
                                toDelete = columnDef;
                        }
                        assert toDelete != null;
                        cfm.removeColumnDefinition(toDelete);
                        break;
                }
                break;
            case OPTS:
                if (cfProps == null)
                    throw new InvalidRequestException(String.format("ALTER COLUMNFAMILY WITH invoked, but no parameters found"));

                cfProps.validate();
                applyPropertiesToCFMetadata(cfm, cfProps);
                break;
        }

        MigrationManager.announceColumnFamilyUpdate(cfm);
    }

    public static void applyPropertiesToCFMetadata(CFMetaData cfm, CFPropDefs cfProps) throws InvalidRequestException, ConfigurationException
    {
        if (cfProps.hasProperty(CFPropDefs.KW_COMMENT))
        {
            cfm.comment(cfProps.get(CFPropDefs.KW_COMMENT));
        }

        cfm.readRepairChance(cfProps.getDouble(CFPropDefs.KW_READREPAIRCHANCE, cfm.getReadRepairChance()));
        cfm.dcLocalReadRepairChance(cfProps.getDouble(CFPropDefs.KW_DCLOCALREADREPAIRCHANCE, cfm.getDcLocalReadRepair()));
        cfm.gcGraceSeconds(cfProps.getInt(CFPropDefs.KW_GCGRACESECONDS, cfm.getGcGraceSeconds()));
        cfm.replicateOnWrite(cfProps.getBoolean(CFPropDefs.KW_REPLICATEONWRITE, cfm.getReplicateOnWrite()));
        cfm.minCompactionThreshold(cfProps.getInt(CFPropDefs.KW_MINCOMPACTIONTHRESHOLD, cfm.getMinCompactionThreshold()));
        cfm.maxCompactionThreshold(cfProps.getInt(CFPropDefs.KW_MAXCOMPACTIONTHRESHOLD, cfm.getMaxCompactionThreshold()));
        cfm.caching(CFMetaData.Caching.fromString(cfProps.getString(CFPropDefs.KW_CACHING, cfm.getCaching().toString())));
        cfm.bloomFilterFpChance(cfProps.getDouble(CFPropDefs.KW_BF_FP_CHANCE, cfm.getBloomFilterFpChance()));

        if (!cfProps.compactionStrategyOptions.isEmpty())
        {
            cfm.compactionStrategyOptions(new HashMap<String, String>(cfProps.compactionStrategyOptions));
        }

        if (!cfProps.compressionParameters.isEmpty())
        {
            cfm.compressionParameters(CompressionParameters.create(cfProps.compressionParameters));
        }
    }

    public String toString()
    {
        return String.format("AlterTableStatement(name=%s, type=%s, column=%s, validator=%s)",
                             cfName,
                             oType,
                             columnName,
                             validator);
    }

}
