/*
 * Copyright 2018-2020 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2
 */
package com.vmware.ddlog;

import com.facebook.presto.sql.parser.ParsingOptions;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Statement;
import ddlogapi.DDlogAPI;
import ddlogapi.DDlogCommand;
import ddlogapi.DDlogException;
import ddlogapi.DDlogRecCommand;
import ddlogapi.DDlogRecord;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.jooq.DSLContext;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;

import javax.annotation.Nullable;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.jooq.impl.DSL.field;


/**
 * This class provides a restricted mechanism to make a DDlog program appear like an SQL database that can be
 * queried over a JDBC connection. To initialize, it requires a set of "create table" and "create view" statements
 * to be supplied during initialization. For example:
 *
 *         final DDlogAPI dDlogAPI = new DDlogAPI(1, null, true);
 *
 *         // Initialise the data provider. ddl represents a list of Strings that are SQL DDL statements
 *         // like "create table" and "create view"
 *         MockDataProvider provider = new DDlogJooqProvider(dDlogAPI, ddl);
 *         MockConnection connection = new MockConnection(provider);
 *
 *         // Pass the mock connection to a jOOQ DSLContext:
 *         DSLContext create = DSL.using(connection);
 *
 * After that, the connection that is created with this MockProvider can execute a restricted subset of SQL queries.
 * We assume these queries are of one of the following forms:
 *   A1. "select * from T" where T is a table name which corresponds to a ddlog output relation. By definition,
 *                        T can therefore only be an SQL view for which there is a corresponding
 *                        "create view T as ..." that is passed to the  DdlogJooqProvider.
 *   A2. "insert into T values (<row>)" where T is a base table. That is, there should be a corresponding
 *                                     "create table T..." DDL statement that is passed to the DDlogJooqProvider.
 *   A3. "delete from T where P1 = A and P2 = B..." where T is a base table and P1, P2... are columns in T's
 *                                         primary key. That is, there should be a corresponding
 *                                        "create table T..." DDL statement that is passed to the DDlogJooqProvider
 *                                         where P1, P2... etc are columns in T's primary key.
 */
public final class DDlogJooqProvider implements MockDataProvider {
    private static final String DDLOG_SOME = "ddlog_std::Some";
    private static final String DDLOG_NONE = "ddlog_std::None";
    private static final Object[] DEFAULT_BINDING = new Object[0];
    private final DDlogAPI dDlogAPI;
    private final DSLContext dslContext;
    private final Field<Integer> updateCountField;
    private final Map<String, List<Field<?>>> tablesToFields = new HashMap<>();
    private final Map<String, List<? extends Field<?>>> tablesToPrimaryKeys = new HashMap<>();
    private final SqlParser parser = new SqlParser();
    private final ParsingOptions options = ParsingOptions.builder().build();
    private final ParseLiterals parseLiterals = new ParseLiterals();
    private final TranslateCreateTableDialect translateCreateTableDialect = new TranslateCreateTableDialect();
    private final Map<String, Set<Record>> materializedViews = new ConcurrentHashMap<>();

    public DDlogJooqProvider(final DDlogAPI dDlogAPI, final List<String> sqlStatements) {
        this.dDlogAPI = dDlogAPI;
        this.dslContext = DSL.using("jdbc:h2:mem:");
        this.updateCountField = field("UPDATE_COUNT", Integer.class);

        // We translate DDL statements from the Presto dialect to H2.
        // We then execute these statements in a temporary database so that JOOQ can extract useful metadata
        // that we will use later (for example, the record types for views).
        for (final String sql : sqlStatements) {
            final Statement statement = parser.createStatement(sql, options);
            final String statementInH2Dialect = translateCreateTableDialect.process(statement, sql);
            dslContext.execute(statementInH2Dialect);
        }
        for (final Table<?> table: dslContext.meta().getTables()) {
            if (table.getSchema().getName().equals("PUBLIC")) { // H2-specific assumption
                tablesToFields.put(table.getName(), Arrays.asList(table.fields()));
                if (table.getPrimaryKey() != null) {
                    tablesToPrimaryKeys.put(table.getName(), table.getPrimaryKey().getFields());
                }
            }
        }
    }

    /*
     * All executed SQL queries against a JOOQ connection are received here
     */
    @Override
    public MockResult[] execute(final MockExecuteContext ctx) {
        final String[] batchSql = ctx.batchSQL();
        final MockResult[] mock = new MockResult[batchSql.length];
        try {
            dDlogAPI.transactionStart();
            final Object[][] bindings = ctx.batchBindings();
            for (int i = 0; i < batchSql.length; i++) {
                final Object[] binding = bindings != null && bindings.length > i ? bindings[i] : DEFAULT_BINDING;
                final QueryContext context = new QueryContext(batchSql[i], binding);
                final org.apache.calcite.sql.parser.SqlParser parser =
                        org.apache.calcite.sql.parser.SqlParser.create(batchSql[i]);
                final SqlNode sqlNode = parser.parseStmt();
                mock[i] = sqlNode.accept(new QueryVisitor(context));
            }
            dDlogAPI.transactionCommitDumpChanges(this::onChange);
        } catch (final DDlogException | RuntimeException | SqlParseException e) {
            try {
                dDlogAPI.transactionRollback();
            } catch (DDlogException rollbackFailed) {
                throw new RuntimeException(rollbackFailed);
            }
        }
        return mock;
    }

    private void onChange(final DDlogCommand<DDlogRecord> command) {
        final int relationId = command.relid();
        final String relationName = dDlogAPI.getTableName(relationId);
        final String tableName = relationNameToTableName(relationName);
        final List<Field<?>> fields = tablesToFields.get(tableName);
        final DDlogRecord record = command.value();
        final Record jooqRecord = dslContext.newRecord(fields);
        for (int i = 0; i < fields.size(); i++) {
            structToValue(fields.get(i), record.getStructField(i), jooqRecord);
        }
        final Set<Record> materializedView = materializedViews.computeIfAbsent(tableName, (k) -> new LinkedHashSet<>());
        switch (command.kind()) {
            case Insert:
                materializedView.add(jooqRecord);
                break;
            case DeleteKey:
                throw new RuntimeException("Did not expect DeleteKey command type");
            case DeleteVal:
                materializedView.remove(jooqRecord);
                break;
        }
    }

    private class QueryVisitor extends SqlBasicVisitor<MockResult> {
        private final QueryContext context;

        QueryVisitor(final QueryContext context) {
            this.context = context;
        }

        @Override
        public MockResult visit(final SqlCall call) {
            switch (call.getKind()) {
                case SELECT:
                    return visitSelect(call);
                case UPDATE:
                    break;
                case INSERT:
                    return visitInsert(call);
                case DELETE:
                    return visitDelete(call);
                default:
                    throw new UnsupportedOperationException(call.toString());
            }
            throw new RuntimeException(call.toString());
        }

        private MockResult visitSelect(final SqlCall call) {
            // The checks below encode assumption A1 (see javadoc for the DDlogJooqProvider class)
            final SqlSelect select = (SqlSelect) call;
            if (!(select.getSelectList().size() == 1
                    && select.getSelectList().get(0).toString().equals("*"))) {
                throw new RuntimeException("Statement not supported: " + context.sql());
            }
            final String tableName = ((SqlIdentifier) select.getFrom()).getSimple();
            final List<Field<?>> fields = tablesToFields.get(tableName.toUpperCase());
            if (fields == null) {
                throw new RuntimeException(String.format("Unknown table %s queried in statement: %s", tableName,
                        context.sql()));
            }
            final Result<Record> result = dslContext.newResult(fields);
            result.addAll(materializedViews.computeIfAbsent(tableName.toUpperCase(), (k) -> new LinkedHashSet<>()));
            return new MockResult(1, result);
        }

        private MockResult visitInsert(final SqlCall call) {
            final SqlInsert insert = (SqlInsert) call;
            if (insert.getSource().getKind() != SqlKind.VALUES) {
                throw new UnsupportedOperationException(call.toString());
            }
            final SqlNode[] values = ((SqlBasicCall) insert.getSource()).getOperands();
            final String tableName = ((SqlIdentifier) insert.getTargetTable()).getSimple();
            final List<Field<?>> fields = tablesToFields.get(tableName.toUpperCase());
            final int tableId = dDlogAPI.getTableId(ddlogRelationName(tableName));
            for (final SqlNode value: values) {
                if (value.getKind() != SqlKind.ROW) {
                    throw new UnsupportedOperationException(call.toString());
                }
                final SqlNode[] rowElements = ((SqlBasicCall) value).operands;
                final DDlogRecord[] recordsArray = new DDlogRecord[rowElements.length];
                if (context.hasBinding()) {
                    // Is a statement with bound variables
                    for (int i = 0; i < rowElements.length; i++) {
                        final boolean isNullableField = fields.get(i).getDataType().nullable();
                        final DDlogRecord record = toValue(fields.get(i), context.nextBinding());
                        recordsArray[i] = maybeOption(isNullableField, record);
                    }
                }
                else {
                    // need to parse literals into DDLogRecords
                    for (int i = 0; i < rowElements.length; i++) {
                        final boolean isNullableField = fields.get(i).getDataType().nullable();
                        final DDlogRecord result = rowElements[i].accept(parseLiterals);
                        recordsArray[i] = maybeOption(isNullableField, result);
                    }
                }
                final DDlogRecord record;
                try {
                    record = DDlogRecord.makeStruct(ddlogTableTypeName(tableName), recordsArray);
                    final DDlogRecCommand command = new DDlogRecCommand(DDlogCommand.Kind.Insert, tableId, record);
                    dDlogAPI.applyUpdates(new DDlogRecCommand[]{command});
                } catch (final DDlogException e) {
                    throw new RuntimeException(e);
                }
            }
            final Result<Record1<Integer>> result = dslContext.newResult(updateCountField);
            final Record1<Integer> resultRecord = dslContext.newRecord(updateCountField);
            resultRecord.setValue(updateCountField, values.length);
            result.add(resultRecord);
            return new MockResult(values.length, result);
        }

        private MockResult visitDelete(final SqlCall call) {
            // The assertions below, and in the ParseWhereClauseForDeletes visitor encode assumption A3
            // (see javadoc for the DDlogJooqProvider class)
            final SqlDelete delete = (SqlDelete) call;
            final String tableName = ((SqlIdentifier) delete.getTargetTable()).getSimple();
            if (delete.getCondition() == null) {
                throw new RuntimeException("Delete queries without where clauses are unsupported: " + context.sql());
            }
            try {
                final SqlBasicCall where = (SqlBasicCall) delete.getCondition();
                final ParseWhereClauseForDeletes visitor = new ParseWhereClauseForDeletes(tableName, context);
                where.accept(visitor);
                final DDlogRecord[] matchExpression = visitor.matchExpressions;
                final DDlogRecord record = matchExpression.length > 1 ? DDlogRecord.makeTuple(matchExpression)
                        : matchExpression[0];

                final int tableId = dDlogAPI.getTableId(ddlogRelationName(tableName));
                final DDlogRecCommand command = new DDlogRecCommand(DDlogCommand.Kind.DeleteKey, tableId, record);
                dDlogAPI.applyUpdates(new DDlogRecCommand[]{command});
            } catch (final DDlogException e) {
                throw new RuntimeException(e);
            }
            final Result<Record1<Integer>> result = dslContext.newResult(updateCountField);
            final Record1<Integer> resultRecord = dslContext.newRecord(updateCountField);
            resultRecord.setValue(updateCountField, 1);
            result.add(resultRecord);
            return new MockResult(1, result);
        }
    }

    private class ParseWhereClauseForDeletes extends SqlBasicVisitor<Void> {
        final DDlogRecord[] matchExpressions;
        final String tableName;
        final QueryContext context;

        public ParseWhereClauseForDeletes(final String tableName, final QueryContext context) {
            this.tableName = tableName;
            this.context = context;
            matchExpressions = new DDlogRecord[tablesToPrimaryKeys.get(tableName.toUpperCase()).size()];
        }

        @Override
        public Void visit(final SqlCall call) {
            final SqlBasicCall expr = (SqlBasicCall) call;
            switch (expr.getOperator().getKind()) {
                case AND:
                    return super.visit(call);
                case EQUALS:
                    final SqlNode left = expr.getOperands()[0];
                    final SqlNode right = expr.getOperands()[1];
                    if (context.hasBinding()) {
                        if (left instanceof SqlIdentifier && right instanceof SqlDynamicParam) {
                            setMatchExpression((SqlIdentifier) left, context.nextBinding());
                            return null;
                        } else if (right instanceof SqlIdentifier && left instanceof SqlDynamicParam) {
                            setMatchExpression((SqlIdentifier) right, context.nextBinding());
                            return null;
                        }
                    } else {
                        if (left instanceof SqlIdentifier && right instanceof SqlLiteral) {
                            setMatchExpression((SqlIdentifier) left, (SqlLiteral) right);
                            return null;
                        } else if (right instanceof SqlIdentifier && left instanceof SqlLiteral) {
                            setMatchExpression((SqlIdentifier) right, (SqlLiteral) left);
                            return null;
                        }
                    }
                    throw new RuntimeException("Unexpected comparison expression: " + call);
                default:
                    throw new UnsupportedOperationException("Unsupported expression in where clause");
            }
        }

        private void setMatchExpression(final SqlIdentifier identifier, final SqlLiteral literal) {
            final List<? extends Field<?>> fields = tablesToPrimaryKeys.get(tableName.toUpperCase());

            /*
             * The match-expressions correspond to each column in the primary key, in the same
             * order as the primary key declaration in the SQL create table statement.
             */
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).getUnqualifiedName().last().equalsIgnoreCase(identifier.getSimple())) {
                    final boolean isNullable = fields.get(i).getDataType().nullable();
                    matchExpressions[i] = maybeOption(isNullable, literal.accept(parseLiterals));
                    return;
                }
            }
            throw new RuntimeException(String.format("Field %s being queried is not a primary key in table %s",
                    identifier, tableName));
        }

        private void setMatchExpression(final SqlIdentifier identifier, final Object parameter) {
            final List<? extends Field<?>> fields = tablesToPrimaryKeys.get(tableName.toUpperCase());

            /*
             * The match-expressions correspond to each column in the primary key, in the same
             * order as the primary key declaration in the SQL create table statement.
             */
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).getUnqualifiedName().last().equalsIgnoreCase(identifier.getSimple())) {
                    matchExpressions[i] = toValue(fields.get(i), parameter);
                    return;
                }
            }
            throw new RuntimeException(String.format("Field %s being queried is not a primary key in table %s",
                    identifier, tableName));
        }
    }

    /*
     * Translates literals into corresponding DDlogRecord instances
     */
    private static class ParseLiterals extends SqlBasicVisitor<DDlogRecord> {

        @Override
        public DDlogRecord visit(final SqlLiteral sqlLiteral) {
            switch (sqlLiteral.getTypeName()) {
                case BOOLEAN:
                    return new DDlogRecord(sqlLiteral.booleanValue());
                case DECIMAL:
                    return new DDlogRecord(sqlLiteral.intValue(false));
                case CHAR:
                    try {
                        return new DDlogRecord(sqlLiteral.toValue());
                    } catch (final DDlogException ignored) {
                    }
                default:
                    throw new UnsupportedOperationException(sqlLiteral.toValue());
            }
        }
    }

    /*
     * This corresponds to the naming convention followed by the SQL -> DDlog compiler
     */
    private static String ddlogTableTypeName(final String tableName) {
        return "T" + tableName.toLowerCase();
    }

    /*
     * This corresponds to the naming convention followed by the SQL -> DDlog compiler
     */
    private static String ddlogRelationName(final String tableName) {
        return "R" + tableName.toLowerCase();
    }

    /*
     * This corresponds to the naming convention followed by the SQL -> DDlog compiler
     */
    private static String relationNameToTableName(final String relationName) {
        return relationName.substring(1).toUpperCase();
    }

    /*
     * The SQL -> DDlog compiler represents nullable fields as ddlog Option<> types. We therefore
     * wrap DDlogRecords if needed.
     */
    private static DDlogRecord maybeOption(final Boolean isNullable, final DDlogRecord record) {
        if (isNullable) {
            try {
                final DDlogRecord[] arr = new DDlogRecord[1];
                arr[0] = record;
                return DDlogRecord.makeStruct(DDLOG_SOME, arr);
            } catch (final DDlogException e) {
                throw new RuntimeException(e);
            }
        } else {
            return record;
        }
    }

    @Nullable
    private static void structToValue(final Field<?> field, final DDlogRecord record, final Record jooqRecord) {
        final boolean isStruct = record.isStruct();
        if (isStruct) {
            final String structName = record.getStructName();
            if (structName.equals(DDLOG_NONE)) {
                jooqRecord.setValue(field, null);
                return;
            }
            if (structName.equals(DDLOG_SOME)) {
                setValue(field, record.getStructField(0), jooqRecord);
                return;
            }
        }
        setValue(field, record, jooqRecord);
    }

    private static DDlogRecord toValue(final Field<?> field, final Object in) {
        final DataType<?> dataType = field.getDataType();
        switch (dataType.getSQLType()) {
            case Types.BOOLEAN:
                return new DDlogRecord((boolean) in);
            case Types.INTEGER:
                return new DDlogRecord((int) in);
            case Types.BIGINT:
                return new DDlogRecord((long) in);
            case Types.VARCHAR:
                try {
                    return new DDlogRecord((String) in);
                } catch (final DDlogException e) {
                    throw new RuntimeException("Could not create String DDlogRecord for object: " + in);
                }
            default:
                throw new RuntimeException("Unknown datatype " + field);
        }
    }

    private static void setValue(final Field<?> field, final DDlogRecord in, final Record out) {
        final DataType<?> dataType = field.getDataType();
        switch (dataType.getSQLType()) {
            case Types.BOOLEAN:
                out.setValue((Field<Boolean>) field, in.getBoolean());
                return;
            case Types.INTEGER:
                out.setValue((Field<Integer>) field, in.getInt().intValue());
                return;
            case Types.BIGINT:
                out.setValue((Field<Long>) field, in.getInt().longValue());
                return;
            case Types.VARCHAR:
                out.setValue((Field<String>) field, in.getString());
                return;
            default:
                throw new RuntimeException("Unknown datatype " + field);
        }
    }

    private static final class QueryContext {
        final String sql;
        final Object[] binding;
        int bindingIndex = 0;

        QueryContext(final String sql, final Object[] binding) {
            this.sql = sql;
            this.binding = binding;
        }

        public String sql() {
            return sql;
        }

        public Object nextBinding() {
            final Object ret = binding[bindingIndex];
            bindingIndex++;
            return ret;
        }

        public boolean hasBinding() {
            return binding.length > 0;
        }
    }
}