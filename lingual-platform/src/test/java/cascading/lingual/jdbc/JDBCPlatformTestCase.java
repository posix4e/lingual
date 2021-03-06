/*
 * Copyright (c) 2007-2014 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cascading.lingual.jdbc;

import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Types;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import cascading.lingual.LingualPlatformTestCase;
import cascading.lingual.catalog.Format;
import cascading.lingual.catalog.Protocol;
import cascading.lingual.catalog.SchemaCatalog;
import cascading.lingual.catalog.SchemaCatalogManager;
import cascading.lingual.platform.PlatformBrokerFactory;
import cascading.lingual.type.SQLDateTimeCoercibleType;
import cascading.lingual.type.SQLTypeResolver;
import cascading.operation.DebugLevel;
import cascading.tap.SinkMode;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryIterator;
import cascading.tuple.type.CoercibleType;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import org.eigenbase.sql.type.BasicSqlType;

import static com.google.common.collect.Maps.newHashMap;

/**
 *
 */
public abstract class JDBCPlatformTestCase extends LingualPlatformTestCase
  {
  public static final String URI = "jdbc:lingual";
  public static final String DRIVER_CLASSNAME = cascading.lingual.jdbc.Driver.class.getName();

  public static final String TEST_ROOT = DATA_PATH + "expected/";

  private Connection connection;

  @Override
  public void setUp() throws Exception
    {
    super.setUp();

    // leave enabled for now
    enableLogging( "cascading.lingual", "debug" );
    }

  protected String getPlannerDebug()
    {
    return DebugLevel.NONE.toString();
    }

  protected abstract String getDefaultSchemaPath();

  public String getConnectionString()
    {
    String platformName = getPlatformName();

    HashMap<Object, Object> values = newHashMap();

    values.put( Driver.SCHEMAS_PROP, getDefaultSchemaPath() );
    values.put( Driver.CATALOG_PROP, getCatalogPath() );
    values.put( Driver.RESULT_PATH_PROP, getResultPath() );
    values.put( Driver.FLOW_PLAN_PATH, getFlowPlanPath() );
    values.put( Driver.SQL_PLAN_PATH_PROP, getSQLPlanPath() );
    values.put( Driver.PLANNER_DEBUG, getPlannerDebug() );

    String properties = Joiner.on( ';' ).withKeyValueSeparator( "=" ).join( values );

    return String.format( "%s:%s;%s", URI, platformName, properties );
    }

  protected synchronized Connection getConnection() throws Exception
    {
    if( connection == null )
      {
      Class.forName( DRIVER_CLASSNAME );
      connection = DriverManager.getConnection( getConnectionString() );

      PlatformBrokerFactory.instance().reloadBrokers();
      }

    return connection;
    }

  protected JavaTypeFactory getTypeFactory()
    {
    try
      {
      return ( (LingualConnection) getConnection() ).getTypeFactory();
      }
    catch( Exception exception )
      {
      throw new RuntimeException( "could not get connection", exception );
      }
    }

  @Override
  public void tearDown() throws Exception
    {
    try
      {
      if( connection != null )
        connection.close();
      }
    finally
      {
      super.tearDown();
      }
    }

  protected void setResultsTo( String schemaName, String tableName, Fields fields ) throws Exception
    {
    addTable( schemaName, tableName, getResultPath() + "/results.tcsv", fields );
    }

  protected TupleEntryIterator getTable( String tableName ) throws IOException
    {
    Tap tap = getPlatform().getDelimitedFile( ",", "\"", new SQLTypeResolver(), TEST_ROOT + tableName + ".tcsv", SinkMode.KEEP );

    tap.retrieveSourceFields( getPlatform().getFlowProcess() );

    return tap.openForRead( getPlatform().getFlowProcess() );
    }

  protected void addTable( String schemaName, String tableName, String identifier, Fields fields ) throws Exception
    {
    addTable( schemaName, tableName, identifier, fields, null, null );
    }

  protected void addTable( String schemaName, String tableName, String identifier, Fields fields, String protocolName, String formatName ) throws Exception
    {
    LingualConnection connection = (LingualConnection) getConnection();

    SchemaCatalogManager catalogManager = connection.getPlatformBroker().getCatalogManager();
    SchemaCatalog schemaCatalog = catalogManager.getSchemaCatalog();

    if( !schemaCatalog.schemaExists( schemaName ) )
      schemaCatalog.addSchemaDef( schemaName, Protocol.getProtocol( protocolName ), Format.getFormat( formatName ), null );

    catalogManager.createTableDefFor( schemaName, tableName, identifier, fields, protocolName, formatName );

    catalogManager.addSchemasTo( connection );
    }

  protected ResultSet executeSql( String sql ) throws Exception
    {
    return getConnection().createStatement().executeQuery( sql );
    }

  protected int executeUpdateSql( String sql ) throws Exception
    {
    return getConnection().createStatement().executeUpdate( sql );
    }

  protected void assertTableValuesEqual( String tableName, String sqlQuery ) throws Exception
    {
    TupleEntryIterator entryIterator = getTable( tableName );
    Table expectedTable = createTable( entryIterator, true );

    assertTableValuesEqual( expectedTable, sqlQuery );
    }

  protected void assertTablesEqual( String tableName, String sqlQuery ) throws Exception
    {
    TupleEntryIterator entryIterator = getTable( tableName );
    Table expectedTable = createTable( entryIterator );

    assertTablesEqual( expectedTable, sqlQuery );
    }

  protected void assertTableValuesEqual( Table expectedTable, String sqlQuery ) throws Exception
    {
    ResultSet result = executeSql( sqlQuery );
    Table resultTable = createTable( result, true );

    assertEquals( expectedTable, resultTable );
    }

  protected void assertTablesEqual( Table expectedTable, String sqlQuery ) throws Exception
    {
    ResultSet result = executeSql( sqlQuery );
    Table resultTable = createTable( result );

    assertEquals( expectedTable, resultTable );
    }

  protected void assertUpdate( int[] expectedRowCounts, String[] sqlQueries ) throws Exception
    {
    // allows multiple queries to be appended to the same file
    getConnection().setAutoCommit( false );

    for( int i = 0; i < expectedRowCounts.length; i++ )
      {
      int expectedRowCount = expectedRowCounts[ i ];
      String sqlQuery = sqlQueries[ i ];

      assertUpdate( expectedRowCount, sqlQuery );
      }

    getConnection().commit();
    getConnection().setAutoCommit( true ); // restore
    }

  protected void assertUpdate( int expectedRowCount, String sqlQuery ) throws Exception
    {
    int rowCount = executeUpdateSql( sqlQuery );
    assertEquals( expectedRowCount, rowCount );
    }

  protected Table<Integer, Comparable, Object> createTable( TupleEntryIterator entryIterator )
    {
    return createTable( entryIterator, false );
    }

  protected Table<Integer, Comparable, Object> createTable( TupleEntryIterator entryIterator, boolean useOrdinal )
    {
    Table<Integer, Comparable, Object> table = createNullableTable();

    JavaTypeFactory typeFactory = getTypeFactory();
    int row = 0;
    while( entryIterator.hasNext() )
      {
      TupleEntry entry = entryIterator.next();

      for( Comparable field : entry.getFields() )
        {
        // we must coerce into the actual sql type returned by the result-set
        Object value = entry.getObject( field );
        int columnPos = entry.getFields().getPos( field );
        Type type = entry.getFields().getType( columnPos );

        if( value != null && type instanceof BasicSqlType )
          {
          value = ( (CoercibleType) type ).coerce( value, typeFactory.getJavaClass( ( (BasicSqlType) type ) ) );

          // for date-time types, the canonical type (int or long) -- chosen for
          // efficient internal processing -- is not what is returned to the
          // end-user from JDBC (java.sql.Date etc.)
          switch( ( (BasicSqlType) type ).getSqlTypeName() )
            {
            case DATE:
              value = new java.sql.Date( ( (Integer) value ).longValue() * SQLDateTimeCoercibleType.MILLIS_PER_DAY );
              break;
            case TIME:
              value = new Time( ( (Integer) value ).longValue() );
              break;
            case TIMESTAMP:
              value = new java.sql.Date( ( (Long) value ).longValue() );
              break;
            }
          }

        if( useOrdinal )
          field = columnPos;

        if( value != null )
          table.put( row++, field, value );
        }
      }

    return table;
    }

  protected Table<Integer, Comparable, Object> createTable( ResultSet resultSet ) throws SQLException
    {
    return createTable( resultSet, false );
    }

  /**
   * Create table.
   *
   * @param resultSet  the result set
   * @param useOrdinal the use ordinal
   * @return the table
   * @throws SQLException the sQL exception
   */
  protected Table<Integer, Comparable, Object> createTable( ResultSet resultSet, boolean useOrdinal ) throws SQLException
    {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();

    Table<Integer, Comparable, Object> table = createNullableTable();

    int row = 0;
    final Calendar utcCalendar = Calendar.getInstance( TimeZone.getTimeZone( "UTC" ) );

    while( resultSet.next() )
      {
      for( int i = 0; i < columnCount; i++ )
        {
        Object value;
        switch( metaData.getColumnType( i + 1 ) )
          {
          case Types.TIME:
            value = resultSet.getTime( i + 1, utcCalendar );
            break;
          case Types.DATE:
            value = resultSet.getDate( i + 1, utcCalendar );
            break;
          case Types.TIMESTAMP:
            value = resultSet.getTimestamp( i + 1, utcCalendar );
            break;
          default:
            value = resultSet.getObject( i + 1 );
            break;
          }

        Comparable columnLabel = useOrdinal ? i : metaData.getColumnLabel( i + 1 );

        if( value != null )
          table.put( row++, columnLabel, value );
        }
      }

    return table;
    }

  protected Table<Integer, Comparable, Object> createTableWithRows( ResultSet resultSet ) throws SQLException
    {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();

    Table<Integer, Comparable, Object> table = createNullableTable();

    int row = 0;

    while( resultSet.next() )
      {
      for( int i = 0; i < columnCount; i++ )
        {
        Object value = resultSet.getObject( i + 1 );

        if( value != null )
          table.put( row, metaData.getColumnLabel( i + 1 ), value );
        }
      row++;
      }

    return table;
    }

  protected Table<Integer, Comparable, Object> createNullableTable()
    {
    return Tables.newCustomTable(
      Maps.<Integer, Map<Comparable, Object>>newLinkedHashMap(),
      new Supplier<Map<Comparable, Object>>()
      {
      public Map<Comparable, Object> get()
        {
        return Maps.newLinkedHashMap();
        }
      } );
    }
  }
