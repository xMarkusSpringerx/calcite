/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test.catalog;

import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.DynamicRecordTypeImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.schema.CustomColumnResolvingTable;
import org.apache.calcite.schema.ExtensibleTable;
import org.apache.calcite.schema.Path;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.StreamableTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.ModifiableViewTable;
import org.apache.calcite.schema.impl.ViewTable;
import org.apache.calcite.schema.impl.ViewTableMacro;
import org.apache.calcite.sql.SqlAccessType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.validate.SemanticTable;
import org.apache.calcite.sql.validate.SqlModality;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.InitializerExpressionFactory;
import org.apache.calcite.sql2rel.NullInitializerExpressionFactory;
import org.apache.calcite.test.AbstractModifiableTable;
import org.apache.calcite.test.AbstractModifiableView;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Mock implementation of {@link SqlValidatorCatalogReader} which returns tables
 * "EMP", "DEPT", "BONUS", "SALGRADE" (same as Oracle's SCOTT schema).
 * Also two streams "ORDERS", "SHIPMENTS";
 * and a view "EMP_20".
 */
public abstract class MockCatalogReader extends CalciteCatalogReader {
  static final String DEFAULT_CATALOG = "CATALOG";
  static final String DEFAULT_SCHEMA = "SALES";
  static final List<String> PREFIX = ImmutableList.of(DEFAULT_SCHEMA);
  private static final Schema DUMMY_SCHEMA = new AbstractSchema();

  /**
   * Creates a MockCatalogReader.
   *
   * <p>Caller must then call {@link #init} to populate with data;
   * constructor is protected to encourage you to define a {@code create}
   * method in each concrete sub-class.
   *
   * @param typeFactory Type factory
   */
  protected MockCatalogReader(RelDataTypeFactory typeFactory,
      boolean caseSensitive) {
    super(CalciteSchema.createRootSchema(false, false, DEFAULT_CATALOG),
        SqlNameMatchers.withCaseSensitive(caseSensitive),
        ImmutableList.of(PREFIX, ImmutableList.of()),
        typeFactory, CalciteConnectionConfig.DEFAULT);
  }

  @Override public boolean isCaseSensitive() {
    return nameMatcher.isCaseSensitive();
  }

  @Override public SqlNameMatcher nameMatcher() {
    return nameMatcher;
  }

  /**
   * Initializes this catalog reader.
   */
  public abstract MockCatalogReader init();

  protected void registerTablesWithRollUp(MockSchema schema, Fixture f) {
    // Register "EMP_R" table. Contains a rolled up column.
    final MockTable empRolledTable =
            MockTable.create(this, schema, "EMP_R", false, 14);
    empRolledTable.addColumn("EMPNO", f.intType, true);
    empRolledTable.addColumn("DEPTNO", f.intType);
    empRolledTable.addColumn("SLACKER", f.booleanType);
    empRolledTable.addColumn("SLACKINGMIN", f.intType);
    empRolledTable.registerRolledUpColumn("SLACKINGMIN");
    registerTable(empRolledTable);

    // Register the "DEPT_R" table. Doesn't contain a rolled up column,
    // but is useful for testing join
    MockTable deptSlackingTable = MockTable.create(this, schema, "DEPT_R", false, 4);
    deptSlackingTable.addColumn("DEPTNO", f.intType, true);
    deptSlackingTable.addColumn("SLACKINGMIN", f.intType);
    registerTable(deptSlackingTable);

    // Register nested schema NEST that contains table with a rolled up column.
    MockSchema nestedSchema = new MockSchema("NEST");
    registerNestedSchema(schema, nestedSchema);

    // Register "EMP_R" table which contains a rolled up column in NEST schema.
    ImmutableList<String> tablePath =
        ImmutableList.of(schema.getCatalogName(), schema.name, nestedSchema.name, "EMP_R");
    final MockTable nestedEmpRolledTable = MockTable.create(this, tablePath, false, 14);
    nestedEmpRolledTable.addColumn("EMPNO", f.intType, true);
    nestedEmpRolledTable.addColumn("DEPTNO", f.intType);
    nestedEmpRolledTable.addColumn("SLACKER", f.booleanType);
    nestedEmpRolledTable.addColumn("SLACKINGMIN", f.intType);
    nestedEmpRolledTable.registerRolledUpColumn("SLACKINGMIN");
    registerTable(nestedEmpRolledTable);
  }

  //~ Methods ----------------------------------------------------------------

  protected void registerType(final List<String> names, final RelProtoDataType relProtoDataType) {
    assert names.get(0).equals(DEFAULT_CATALOG);
    final List<String> schemaPath = Util.skipLast(names);
    final CalciteSchema schema =
        SqlValidatorUtil.getSchema(rootSchema,
            schemaPath, SqlNameMatchers.withCaseSensitive(true));
    requireNonNull(schema, "schema");
    schema.add(Util.last(names), relProtoDataType);
  }

  protected void registerTable(final MockTable table) {
    table.onRegister(typeFactory);
    final WrapperTable wrapperTable = new WrapperTable(table);
    if (table.stream) {
      registerTable(table.names,
          new StreamableWrapperTable(table) {
            @Override public Table stream() {
              return wrapperTable;
            }
          });
    } else {
      registerTable(table.names, wrapperTable);
    }
  }

  void registerTable(MockDynamicTable table) {
    registerTable(table.names, table);
  }

  void reregisterTable(MockDynamicTable table) {
    List<String> names = table.names;
    assert names.get(0).equals(DEFAULT_CATALOG);
    List<String> schemaPath = Util.skipLast(names);
    String tableName = Util.last(names);
    CalciteSchema schema =
        SqlValidatorUtil.getSchema(rootSchema,
            schemaPath, SqlNameMatchers.withCaseSensitive(true));
    requireNonNull(schema, "schema");
    schema.removeTable(tableName);
    schema.add(tableName, table);
  }

  private void registerTable(final List<String> names, final Table table) {
    assert names.get(0).equals(DEFAULT_CATALOG);
    final List<String> schemaPath = Util.skipLast(names);
    final String tableName = Util.last(names);
    final CalciteSchema schema =
        SqlValidatorUtil.getSchema(rootSchema,
            schemaPath, SqlNameMatchers.withCaseSensitive(true));
    requireNonNull(schema, "schema");
    schema.add(tableName, table);
  }

  protected void registerSchema(MockSchema schema) {
    rootSchema.add(schema.name, new AbstractSchema());
  }

  private void registerNestedSchema(MockSchema parentSchema, MockSchema schema) {
    final CalciteSchema subSchema =
        rootSchema.getSubSchema(parentSchema.getName(), true);
    requireNonNull(subSchema, "subSchema");
    subSchema.add(schema.name, new AbstractSchema());
  }

  private static List<RelCollation> deduceMonotonicity(
      Prepare.PreparingTable table) {
    final List<RelCollation> collationList = new ArrayList<>();

    // Deduce which fields the table is sorted on.
    int i = -1;
    for (RelDataTypeField field : table.getRowType().getFieldList()) {
      ++i;
      final SqlMonotonicity monotonicity =
          table.getMonotonicity(field.getName());
      if (monotonicity != SqlMonotonicity.NOT_MONOTONIC) {
        final RelFieldCollation.Direction direction =
            monotonicity.isDecreasing()
                ? RelFieldCollation.Direction.DESCENDING
                : RelFieldCollation.Direction.ASCENDING;
        collationList.add(
            RelCollations.of(
                new RelFieldCollation(i, direction)));
      }
    }
    return collationList;
  }

  //~ Inner Classes ----------------------------------------------------------

  /** Column resolver. */
  public interface ColumnResolver {
    List<Pair<RelDataTypeField, List<String>>> resolveColumn(
        RelDataType rowType, RelDataTypeFactory typeFactory, List<String> names);
  }

  /** Mock schema. */
  public static class MockSchema {
    private final List<String> tableNames = new ArrayList<>();
    private final String name;

    public MockSchema(String name) {
      this.name = name;
    }

    public void addTable(String name) {
      tableNames.add(name);
    }

    public String getCatalogName() {
      return DEFAULT_CATALOG;
    }

    public String getName() {
      return name;
    }
  }

  /**
   * Mock implementation of
   * {@link org.apache.calcite.prepare.Prepare.PreparingTable}.
   */
  public static class MockTable extends Prepare.AbstractPreparingTable
      implements BuiltInMetadata.MaxRowCount.Handler {

    protected final MockCatalogReader catalogReader;
    protected final boolean stream;
    protected final double rowCount;
    protected final List<Map.Entry<String, RelDataType>> columnList =
        new ArrayList<>();
    protected final List<ImmutableBitSet> keyList = new ArrayList<>();
    protected final List<RelReferentialConstraint> referentialConstraints =
        new ArrayList<>();
    protected RelDataType rowType;
    protected List<RelCollation> collationList;
    protected final List<String> names;
    protected final Double maxRowCount;
    protected final Set<String> monotonicColumnSet = new HashSet<>();
    protected StructKind kind = StructKind.FULLY_QUALIFIED;
    protected final @Nullable ColumnResolver resolver;
    private final boolean temporal;
    protected final InitializerExpressionFactory initializerFactory;
    protected final Set<String> rolledUpColumns = new HashSet<>();

    /** Wrapped objects that can be obtained by calling
     * {@link #unwrap(Class)}. Initially an immutable list, but converted to
     * a mutable array list on first assignment. */
    protected List<Object> wraps;

    public MockTable(MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name, boolean stream, boolean temporal,
        double rowCount, @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory) {
      this(catalogReader, ImmutableList.of(catalogName, schemaName, name),
          stream, temporal, rowCount, resolver, initializerFactory,
          ImmutableList.of(), Double.POSITIVE_INFINITY);
    }

    public MockTable(MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name, boolean stream, boolean temporal,
        double rowCount, @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory, Double maxRowCount) {
      this(catalogReader, ImmutableList.of(catalogName, schemaName, name),
          stream, temporal, rowCount, resolver, initializerFactory,
          ImmutableList.of(), maxRowCount);
    }

    public void registerRolledUpColumn(String columnName) {
      rolledUpColumns.add(columnName);
    }

    private MockTable(MockCatalogReader catalogReader, List<String> names,
        boolean stream, boolean temporal, double rowCount,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory, List<Object> wraps,
        Double maxRowCount) {
      this.catalogReader = catalogReader;
      this.stream = stream;
      this.temporal = temporal;
      this.rowCount = rowCount;
      this.names = names;
      this.resolver = resolver;
      this.initializerFactory = initializerFactory;
      this.wraps = ImmutableList.copyOf(wraps);
      this.maxRowCount = maxRowCount;
    }

    /**
     * Copy constructor.
     */
    protected MockTable(MockCatalogReader catalogReader, boolean stream,
        boolean temporal, double rowCount,
        List<Map.Entry<String, RelDataType>> columnList, List<ImmutableBitSet> keyList,
        RelDataType rowType, List<RelCollation> collationList, List<String> names,
        Set<String> monotonicColumnSet, StructKind kind,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory) {
      this.catalogReader = catalogReader;
      this.stream = stream;
      this.temporal = temporal;
      this.rowCount = rowCount;
      this.rowType = rowType;
      this.collationList = collationList;
      this.names = names;
      this.kind = kind;
      this.resolver = resolver;
      this.maxRowCount = Double.POSITIVE_INFINITY;
      this.initializerFactory = initializerFactory;
      for (String name : monotonicColumnSet) {
        addMonotonic(name);
      }
      this.wraps = ImmutableList.of();
    }

    public void addWrap(Object wrap) {
      if (wraps instanceof ImmutableList) {
        wraps = new ArrayList<>(wraps);
      }
      wraps.add(wrap);
    }

    @Override public @Nullable Double getMaxRowCount(RelNode r, RelMetadataQuery mq) {
      return maxRowCount;
    }

    @Override public MetadataDef<BuiltInMetadata.MaxRowCount> getDef() {
      return BuiltInMetadata.MaxRowCount.Handler.super.getDef();
    }

    /** Implementation of AbstractModifiableTable. */
    private class ModifiableTable extends AbstractModifiableTable
        implements ExtensibleTable, Wrapper {
      protected ModifiableTable(String tableName) {
        super(tableName);
      }

      @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return typeFactory.createStructType(MockTable.this.getRowType().getFieldList());
      }

      @Override public Collection getModifiableCollection() {
        throw new UnsupportedOperationException();
      }

      @Override public <E> Queryable<E>
      asQueryable(QueryProvider queryProvider, SchemaPlus schema,
          String tableName) {
        throw new UnsupportedOperationException();
      }

      @Override public Type getElementType() {
        return Void.class;
      }

      @Override public Expression getExpression(SchemaPlus schema,
          String tableName, Class clazz) {
        throw new UnsupportedOperationException();
      }

      @Override public <C> C unwrap(Class<C> aClass) {
        if (aClass.isInstance(initializerFactory)) {
          return aClass.cast(initializerFactory);
        } else if (aClass.isInstance(MockTable.this)) {
          return aClass.cast(MockTable.this);
        }
        return super.unwrap(aClass);
      }

      @Override public Table extend(final List<RelDataTypeField> fields) {
        return new ModifiableTable(Util.last(names)) {
          @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            ImmutableList<RelDataTypeField> allFields =
                ImmutableList.copyOf(
                    Iterables.concat(
                        ModifiableTable.this.getRowType(typeFactory).getFieldList(),
                        fields));
            return typeFactory.createStructType(allFields);
          }
        };
      }

      @Override public int getExtendedColumnOffset() {
        return rowType.getFieldCount();
      }

      @Override public boolean isRolledUp(String column) {
        return rolledUpColumns.contains(column);
      }

      @Override public boolean rolledUpColumnValidInsideAgg(String column,
          SqlCall call, @Nullable SqlNode parent, @Nullable CalciteConnectionConfig config) {
        // For testing
        return call.getKind() != SqlKind.MAX
            && parent != null
            && (parent.getKind() == SqlKind.SELECT
                || parent.getKind() == SqlKind.FILTER);
      }
    }

    @Override protected RelOptTable extend(final Table extendedTable) {
      return new MockTable(catalogReader, names, stream, temporal, rowCount,
          resolver, initializerFactory, wraps, maxRowCount) {
        @Override public RelDataType getRowType() {
          return extendedTable.getRowType(catalogReader.typeFactory);
        }
      };
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount) {
      return create(catalogReader, schema, name, stream, rowCount, null);
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount, double maxRowCount) {
      return create(catalogReader, schema, name, stream, rowCount, null, maxRowCount);
    }

    public static MockTable create(MockCatalogReader catalogReader,
        List<String> names, boolean stream, double rowCount) {
      return new MockTable(catalogReader, names, stream, false, rowCount, null,
          NullInitializerExpressionFactory.INSTANCE, ImmutableList.of(), Double.POSITIVE_INFINITY);
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount,
        @Nullable ColumnResolver resolver) {
      return create(catalogReader, schema, name, stream, rowCount, resolver,
          NullInitializerExpressionFactory.INSTANCE, false);
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount,
        @Nullable ColumnResolver resolver, double maxRowCount) {
      return create(catalogReader, schema, name, stream, rowCount, resolver,
          NullInitializerExpressionFactory.INSTANCE, false, maxRowCount);
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory,
        boolean temporal, Double maxRowCount) {
      MockTable table =
          new MockTable(catalogReader, schema.getCatalogName(), schema.name,
              name, stream, temporal, rowCount, resolver,
              initializerExpressionFactory, maxRowCount);
      schema.addTable(name);
      return table;
    }

    public static MockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory,
        boolean temporal) {
      MockTable table =
          new MockTable(catalogReader, schema.getCatalogName(), schema.name,
              name, stream, temporal, rowCount, resolver,
              initializerExpressionFactory);
      schema.addTable(name);
      return table;
    }

    @Override public <T> T unwrap(Class<T> clazz) {
      if (clazz.isInstance(this)) {
        return clazz.cast(this);
      }
      if (clazz.isInstance(initializerFactory)) {
        return clazz.cast(initializerFactory);
      }
      if (clazz.isAssignableFrom(Table.class)) {
        final Table table = resolver == null
            ? new ModifiableTable(Util.last(names))
                : new ModifiableTableWithCustomColumnResolving(Util.last(names));
        return clazz.cast(table);
      }
      for (Object handler : wraps) {
        if (clazz.isInstance(handler)) {
          return clazz.cast(handler);
        }
      }
      return null;
    }

    @Override public double getRowCount() {
      return rowCount;
    }

    @Override public RelOptSchema getRelOptSchema() {
      return catalogReader;
    }

    @Override public RelNode toRel(ToRelContext context) {
      return LogicalTableScan.create(context.getCluster(), this, context.getTableHints());
    }

    @Override public List<RelCollation> getCollationList() {
      return collationList;
    }

    @Override public RelDistribution getDistribution() {
      return RelDistributions.BROADCAST_DISTRIBUTED;
    }

    @Override public boolean isKey(ImmutableBitSet columns) {
      return keyList.stream().anyMatch(columns::contains);
    }

    @Override public List<ImmutableBitSet> getKeys() {
      return keyList;
    }

    @Override public List<RelReferentialConstraint> getReferentialConstraints() {
      return referentialConstraints;
    }

    @Override public RelDataType getRowType() {
      return rowType;
    }

    @Override public boolean supportsModality(SqlModality modality) {
      return modality == (stream ? SqlModality.STREAM : SqlModality.RELATION);
    }

    @Override public boolean isTemporal() {
      return temporal;
    }

    public void onRegister(RelDataTypeFactory typeFactory) {
      rowType =
          typeFactory.createStructType(kind, Pair.right(columnList),
              Pair.left(columnList));
      collationList = deduceMonotonicity(this);
    }

    @Override public List<String> getQualifiedName() {
      return names;
    }

    @Override public SqlMonotonicity getMonotonicity(String columnName) {
      return monotonicColumnSet.contains(columnName)
          ? SqlMonotonicity.INCREASING
          : SqlMonotonicity.NOT_MONOTONIC;
    }

    @Override public SqlAccessType getAllowedAccess() {
      return SqlAccessType.ALL;
    }

    @Override public Expression getExpression(Class clazz) {
      // Return a true constant just to pass the tests in EnumerableTableScanRule.
      return Expressions.constant(true);
    }

    public void addColumn(String name, RelDataType type) {
      addColumn(name, type, false);
    }

    public void addColumn(String name, RelDataType type, boolean isKey) {
      if (isKey) {
        keyList.add(ImmutableBitSet.of(columnList.size()));
      }
      columnList.add(Pair.of(name, type));
    }

    public void addKey(String... columns) {
      ImmutableBitSet.Builder keyBuilder = ImmutableBitSet.builder();
      for (String c : columns) {
        int i = columnIndex(c);
        if (i < 0) {
          throw new IllegalArgumentException("Column " + c + " not found in the table");
        }
        keyBuilder.set(i);
      }
      keyList.add(keyBuilder.build());
    }

    public void addKey(ImmutableBitSet key) {
      for (Integer columnIndex : key) {
        if (columnIndex >= columnList.size()) {
          throw new IllegalArgumentException(
              "Column index " + columnIndex + " exceeds the number of columns");
        }
      }
      keyList.add(key);
    }

    private int columnIndex(String colName) {
      for (int i = 0; i < columnList.size(); i++) {
        Map.Entry<String, RelDataType> col = columnList.get(i);
        if (colName.equals(col.getKey())) {
          return i;
        }
      }
      return -1;
    }

    public void addMonotonic(String name) {
      monotonicColumnSet.add(name);
      assert Pair.left(columnList).contains(name);
    }

    public void setKind(StructKind kind) {
      this.kind = kind;
    }

    public StructKind getKind() {
      return kind;
    }

    /**
     * Subclass of {@link ModifiableTable} that also implements
     * {@link CustomColumnResolvingTable}.
     */
    private class ModifiableTableWithCustomColumnResolving
        extends ModifiableTable implements CustomColumnResolvingTable, Wrapper {

      ModifiableTableWithCustomColumnResolving(String tableName) {
        super(tableName);
        requireNonNull(resolver, "resolver");
      }

      @Override public List<Pair<RelDataTypeField, List<String>>> resolveColumn(
          RelDataType rowType, RelDataTypeFactory typeFactory,
          List<String> names) {
        requireNonNull(resolver, "resolver");
        return resolver.resolveColumn(rowType, typeFactory, names);
      }
    }
  }

  /**
   * Alternative to MockViewTable that exercises code paths in ModifiableViewTable
   * and ModifiableViewTableInitializerExpressionFactory.
   */
  public static class MockModifiableViewRelOptTable extends MockTable {
    private final MockModifiableViewTable modifiableViewTable;

    private MockModifiableViewRelOptTable(MockModifiableViewTable modifiableViewTable,
        MockCatalogReader catalogReader, String catalogName, String schemaName, String name,
        boolean stream, double rowCount, ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory) {
      super(catalogReader, ImmutableList.of(catalogName, schemaName, name),
          stream, false, rowCount, resolver, initializerExpressionFactory,
          ImmutableList.of(), Double.POSITIVE_INFINITY);
      this.modifiableViewTable = modifiableViewTable;
    }

    /**
     * Copy constructor.
     */
    private MockModifiableViewRelOptTable(MockModifiableViewTable modifiableViewTable,
        MockCatalogReader catalogReader, boolean stream, double rowCount,
        List<Map.Entry<String, RelDataType>> columnList, List<ImmutableBitSet> keyList,
        RelDataType rowType, List<RelCollation> collationList, List<String> names,
        Set<String> monotonicColumnSet, StructKind kind,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory) {
      super(catalogReader, stream, false, rowCount, columnList, keyList,
          rowType, collationList, names,
          monotonicColumnSet, kind, resolver, initializerFactory);
      this.modifiableViewTable = modifiableViewTable;
    }

    public static MockModifiableViewRelOptTable create(MockModifiableViewTable modifiableViewTable,
        MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name,
        boolean stream, double rowCount, @Nullable ColumnResolver resolver) {
      final Table underlying = modifiableViewTable.unwrap(Table.class);
      final InitializerExpressionFactory initializerExpressionFactory =
          underlying instanceof Wrapper
              ? ((Wrapper) underlying).unwrap(InitializerExpressionFactory.class)
              : NullInitializerExpressionFactory.INSTANCE;
      return new MockModifiableViewRelOptTable(modifiableViewTable,
          catalogReader, catalogName, schemaName, name, stream, rowCount,
          resolver, Util.first(initializerExpressionFactory,
          NullInitializerExpressionFactory.INSTANCE));
    }

    public static MockViewTableMacro viewMacro(CalciteSchema schema, String viewSql,
        List<String> schemaPath, List<String> viewPath, Boolean modifiable) {
      return new MockViewTableMacro(schema, viewSql, schemaPath, viewPath, modifiable);
    }

    @Override public RelDataType getRowType() {
      return modifiableViewTable.getRowType(catalogReader.typeFactory);
    }

    @Override protected RelOptTable extend(Table extendedTable) {
      return new MockModifiableViewRelOptTable((MockModifiableViewTable) extendedTable,
          catalogReader, stream, rowCount, columnList, keyList, rowType, collationList, names,
          monotonicColumnSet, kind, resolver, initializerFactory);
    }

    @Override public <T> T unwrap(Class<T> clazz) {
      if (clazz.isInstance(modifiableViewTable)) {
        return clazz.cast(modifiableViewTable);
      }
      return super.unwrap(clazz);
    }

    /**
     * A TableMacro that creates mock ModifiableViewTable.
     */
    public static class MockViewTableMacro extends ViewTableMacro {
      MockViewTableMacro(CalciteSchema schema, String viewSql, List<String> schemaPath,
          List<String> viewPath, Boolean modifiable) {
        super(schema, viewSql, schemaPath, viewPath, modifiable);
      }

      @Override protected ModifiableViewTable modifiableViewTable(
          CalcitePrepare.AnalyzeViewResult parsed, String viewSql,
          List<String> schemaPath, List<String> viewPath, CalciteSchema schema) {
        final JavaTypeFactory typeFactory = (JavaTypeFactory) parsed.typeFactory;
        final Type elementType = typeFactory.getJavaClass(parsed.rowType);
        return new MockModifiableViewTable(elementType,
            RelDataTypeImpl.proto(parsed.rowType), viewSql, schemaPath, viewPath,
            parsed.table, Schemas.path(schema.root(), parsed.tablePath),
            parsed.constraint, parsed.columnMapping);
      }
    }

    /**
     * A mock of ModifiableViewTable that can unwrap a mock RelOptTable.
     */
    public static class MockModifiableViewTable extends ModifiableViewTable {
      private final RexNode constraint;

      MockModifiableViewTable(Type elementType, RelProtoDataType rowType,
          String viewSql, List<String> schemaPath, List<String> viewPath,
          Table table, Path tablePath, RexNode constraint,
          ImmutableIntList columnMapping) {
        super(elementType, rowType, viewSql, schemaPath, viewPath, table,
            tablePath, constraint, columnMapping);
        this.constraint = constraint;
      }

      @Override public ModifiableViewTable extend(Table extendedTable,
          RelProtoDataType protoRowType, ImmutableIntList newColumnMapping) {
        return new MockModifiableViewTable(getElementType(), protoRowType,
            getViewSql(), getSchemaPath(), getViewPath(), extendedTable,
            getTablePath(), constraint, newColumnMapping);
      }
    }
  }

  /**
   * Mock implementation of {@link Prepare.AbstractPreparingTable} which holds {@link ViewTable}
   * and delegates {@link MockTable#toRel} call to the view.
   */
  public static class MockRelViewTable extends MockTable {
    private final ViewTable viewTable;

    private MockRelViewTable(ViewTable viewTable,
        MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name,
        boolean stream, double rowCount, @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory) {
      super(catalogReader, ImmutableList.of(catalogName, schemaName, name),
          stream, false, rowCount, resolver, initializerExpressionFactory,
          ImmutableList.of(), Double.POSITIVE_INFINITY);
      this.viewTable = viewTable;
    }

    public static MockRelViewTable create(ViewTable viewTable,
        MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name,
        boolean stream, double rowCount, @Nullable ColumnResolver resolver) {
      Table underlying = viewTable.unwrap(Table.class);
      InitializerExpressionFactory initializerExpressionFactory =
          underlying instanceof Wrapper
              ? ((Wrapper) underlying).unwrap(InitializerExpressionFactory.class)
              : NullInitializerExpressionFactory.INSTANCE;
      return new MockRelViewTable(viewTable,
          catalogReader, catalogName, schemaName, name, stream, rowCount,
          resolver, Util.first(initializerExpressionFactory,
          NullInitializerExpressionFactory.INSTANCE));
    }

    @Override public RelDataType getRowType() {
      return viewTable.getRowType(catalogReader.typeFactory);
    }

    @Override public RelNode toRel(RelOptTable.ToRelContext context) {
      return viewTable.toRel(context, this);
    }

    @Override public <T> T unwrap(Class<T> clazz) {
      if (clazz.isInstance(viewTable)) {
        return clazz.cast(viewTable);
      }
      return super.unwrap(clazz);
    }
  }

  /**
   * Mock implementation of
   * {@link org.apache.calcite.prepare.Prepare.PreparingTable} for views.
   */
  public abstract static class MockViewTable extends MockTable {
    private final MockTable fromTable;
    private final Table table;
    private final ImmutableIntList mapping;

    MockViewTable(MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name, boolean stream, double rowCount,
        MockTable fromTable, ImmutableIntList mapping,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerFactory) {
      super(catalogReader, catalogName, schemaName, name, stream, false,
          rowCount, resolver, initializerFactory);
      this.fromTable = fromTable;
      this.table = fromTable.unwrap(Table.class);
      this.mapping = mapping;
    }

    /** Implementation of AbstractModifiableView. */
    private class ModifiableView extends AbstractModifiableView
        implements Wrapper {
      @Override public Table getTable() {
        return fromTable.unwrap(Table.class);
      }

      @Override public Path getTablePath() {
        final PairList<String, Schema> list = PairList.of();
        fromTable.names.forEach(name -> list.add(name, DUMMY_SCHEMA));
        return Schemas.path(list);
      }

      @Override public ImmutableIntList getColumnMapping() {
        return mapping;
      }

      @Override public RexNode getConstraint(RexBuilder rexBuilder,
          RelDataType tableRowType) {
        return MockViewTable.this.getConstraint(rexBuilder, tableRowType);
      }

      @Override public RelDataType
      getRowType(final RelDataTypeFactory typeFactory) {
        return typeFactory.createStructType(
            new AbstractList<Map.Entry<String, RelDataType>>() {
              @Override public Map.Entry<String, RelDataType>
              get(int index) {
                return table.getRowType(typeFactory).getFieldList()
                    .get(mapping.get(index));
              }

              @Override public int size() {
                return mapping.size();
              }
            });
      }

      @Override public <C> C unwrap(Class<C> aClass) {
        if (table instanceof Wrapper) {
          final C c = ((Wrapper) table).unwrap(aClass);
          if (c != null) {
            return c;
          }
        }
        return super.unwrap(aClass);
      }
    }

    /**
     * Subclass of ModifiableView that also implements
     * CustomColumnResolvingTable.
     */
    private class ModifiableViewWithCustomColumnResolving
        extends ModifiableView implements CustomColumnResolvingTable, Wrapper {
      ModifiableViewWithCustomColumnResolving() {
        requireNonNull(resolver, "resolver");
      }

      @Override public List<Pair<RelDataTypeField, List<String>>> resolveColumn(
          RelDataType rowType, RelDataTypeFactory typeFactory, List<String> names) {
        requireNonNull(resolver, "resolver");
        return resolver.resolveColumn(rowType, typeFactory, names);
      }

      @Override public <C> C unwrap(Class<C> aClass) {
        if (table instanceof Wrapper) {
          final C c = ((Wrapper) table).unwrap(aClass);
          if (c != null) {
            return c;
          }
        }
        return super.unwrap(aClass);
      }
    }

    protected abstract RexNode getConstraint(RexBuilder rexBuilder,
        RelDataType tableRowType);

    @Override public void onRegister(RelDataTypeFactory typeFactory) {
      super.onRegister(typeFactory);
      // To simulate getRowType() behavior in ViewTable.
      final RelProtoDataType protoRowType = RelDataTypeImpl.proto(rowType);
      rowType = protoRowType.apply(typeFactory);
    }

    @Override public RelNode toRel(ToRelContext context) {
      RelNode rel =
          LogicalTableScan.create(context.getCluster(), fromTable,
              context.getTableHints());
      final RexBuilder rexBuilder = context.getCluster().getRexBuilder();
      rel =
          LogicalFilter.create(rel, getConstraint(rexBuilder, rel.getRowType()));
      final List<RelDataTypeField> fieldList =
          rel.getRowType().getFieldList();
      final PairList<RexNode, String> projects = PairList.of();
      mapping.forEachInt(i -> RexInputRef.add2(projects, i, fieldList));
      return LogicalProject.create(rel,
          ImmutableList.of(),
          projects.leftList(),
          projects.rightList(),
          ImmutableSet.of());
    }

    @Override public <T> T unwrap(Class<T> clazz) {
      if (clazz.isAssignableFrom(ModifiableView.class)) {
        ModifiableView view = resolver == null
            ? new ModifiableView()
                : new ModifiableViewWithCustomColumnResolving();
        return clazz.cast(view);
      }
      return super.unwrap(clazz);
    }
  }

  /**
   * Mock implementation of {@link AbstractQueryableTable} with dynamic record
   * type.
   */
  public static class MockDynamicTable
      extends AbstractQueryableTable implements TranslatableTable {
    private final DynamicRecordTypeImpl rowType;
    protected final List<String> names;

    MockDynamicTable(String catalogName, String schemaName, String name) {
      super(Object.class);
      this.names = Arrays.asList(catalogName, schemaName, name);
      this.rowType = new DynamicRecordTypeImpl(new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT));
    }

    @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return rowType;
    }

    @Override public <T> Queryable<T> asQueryable(QueryProvider queryProvider,
        SchemaPlus schema, String tableName) {
      throw new UnsupportedOperationException();
    }

    @Override public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
      return LogicalTableScan.create(context.getCluster(), relOptTable, context.getTableHints());
    }
  }

  /** Mock implementation of {@link MockTable} that supports must-filter fields.
   *
   * <p>Must-filter fields are declared via methods in the {@link SemanticTable}
   * interface. */
  public static class MustFilterMockTable
      extends MockTable implements SemanticTable {
    private final Map<String, String> fieldFilters;
    private final List<Integer> bypassFieldList;

    MustFilterMockTable(MockCatalogReader catalogReader, String catalogName,
        String schemaName, String name, boolean stream, boolean temporal,
        double rowCount, @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory,
        Map<String, String> fieldFilters, List<Integer> bypassFieldList) {
      super(catalogReader, catalogName, schemaName, name, stream, temporal,
          rowCount, resolver, initializerExpressionFactory);
      this.fieldFilters = ImmutableMap.copyOf(fieldFilters);
      this.bypassFieldList = ImmutableList.copyOf(bypassFieldList);
    }

    /** Creates a MustFilterMockTable. */
    public static MustFilterMockTable create(MockCatalogReader catalogReader,
        MockSchema schema, String name, boolean stream, double rowCount,
        @Nullable ColumnResolver resolver,
        InitializerExpressionFactory initializerExpressionFactory,
        boolean temporal, Map<String, String> fieldFilters,
        List<Integer> bypassFieldList) {
      MustFilterMockTable table =
          new MustFilterMockTable(catalogReader, schema.getCatalogName(),
              schema.name, name, stream, temporal, rowCount, resolver,
              initializerExpressionFactory, fieldFilters, bypassFieldList);
      schema.addTable(name);
      return table;
    }

    @Override public @Nullable String getFilter(int column) {
      String columnName = columnList.get(column).getKey();
      return fieldFilters.get(columnName);
    }

    @Override public boolean mustFilter(int column) {
      String columnName = columnList.get(column).getKey();
      return fieldFilters.containsKey(columnName);
    }

    @Override public List<Integer> bypassFieldList() {
      return bypassFieldList;
    }
  }

  /** Wrapper around a {@link MockTable}, giving it a {@link Table} interface.
   * You can get the {@code MockTable} by calling {@link #unwrap(Class)}. */
  private static class WrapperTable implements Table, Wrapper {
    private final MockTable table;

    WrapperTable(MockTable table) {
      this.table = table;
    }

    @Override public <C> C unwrap(Class<C> aClass) {
      return aClass.isInstance(this) ? aClass.cast(this)
          : aClass.isInstance(table) ? aClass.cast(table)
          : null;
    }

    @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return table.getRowType();
    }

    @Override public Statistic getStatistic() {
      return new Statistic() {
        @Override public Double getRowCount() {
          return table.rowCount;
        }

        @Override public boolean isKey(ImmutableBitSet columns) {
          return table.isKey(columns);
        }

        @Override public List<ImmutableBitSet> getKeys() {
          return table.getKeys();
        }

        @Override public List<RelReferentialConstraint> getReferentialConstraints() {
          return table.getReferentialConstraints();
        }

        @Override public List<RelCollation> getCollations() {
          return table.collationList;
        }

        @Override public RelDistribution getDistribution() {
          return table.getDistribution();
        }
      };
    }

    @Override public boolean isRolledUp(String column) {
      return table.rolledUpColumns.contains(column);
    }

    @Override public boolean rolledUpColumnValidInsideAgg(String column,
        SqlCall call, @Nullable SqlNode parent, @Nullable CalciteConnectionConfig config) {
      // For testing
      return call.getKind() != SqlKind.MAX
          && parent != null
          && (parent.getKind() == SqlKind.SELECT
              || parent.getKind() == SqlKind.FILTER);
    }

    @Override public Schema.TableType getJdbcTableType() {
      return table.stream ? Schema.TableType.STREAM : Schema.TableType.TABLE;
    }
  }

  /** Wrapper around a {@link MockTable}, giving it a {@link StreamableTable}
   * interface. */
  private static class StreamableWrapperTable extends WrapperTable
      implements StreamableTable {
    StreamableWrapperTable(MockTable table) {
      super(table);
    }

    @Override public Table stream() {
      return this;
    }
  }

}
