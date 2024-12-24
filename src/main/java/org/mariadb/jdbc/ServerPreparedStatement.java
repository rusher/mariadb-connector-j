// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2024 MariaDB Corporation Ab
package org.mariadb.jdbc;

import static org.mariadb.jdbc.util.constants.Capabilities.*;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.mariadb.jdbc.client.ColumnDecoder;
import org.mariadb.jdbc.client.Completion;
import org.mariadb.jdbc.client.result.CompleteResult;
import org.mariadb.jdbc.client.result.Result;
import org.mariadb.jdbc.client.util.ClosableLock;
import org.mariadb.jdbc.client.util.Parameters;
import org.mariadb.jdbc.export.ExceptionFactory;
import org.mariadb.jdbc.message.ClientMessage;
import org.mariadb.jdbc.message.client.ExecutePacket;
import org.mariadb.jdbc.message.client.PrepareExecutePacket;
import org.mariadb.jdbc.message.client.PreparePacket;
import org.mariadb.jdbc.message.server.OkPacket;
import org.mariadb.jdbc.message.server.PrepareResultPacket;
import org.mariadb.jdbc.util.ParameterList;
import org.mariadb.jdbc.util.timeout.QueryTimeoutHandler;

/**
 * Server prepare statement. command will generate COM_STMT_PREPARE + COM_STMT_EXECUTE (+
 * COM_STMT_CLOSE)
 */
public class ServerPreparedStatement extends BasePreparedStatement {
  private static final Pattern PREPARABLE_STATEMENT_PATTERN =
      Pattern.compile("^(SELECT|UPDATE|INSERT|DELETE|REPLACE|DO|CALL)", Pattern.CASE_INSENSITIVE);

  /**
   * Server prepare statement constructor
   *
   * @param sql command
   * @param con connection
   * @param lock thread safe lock
   * @param canUseServerTimeout can server use timeout
   * @param canUseServerMaxRows can server use max rows
   * @param canCachePrepStmts can server cache prepared statement
   * @param autoGeneratedKeys must command return automatically generated keys
   * @param resultSetType resultset type
   * @param resultSetConcurrency resultset concurrency
   * @param defaultFetchSize default fetch size
   * @throws SQLException if prepare fails
   */
  @SuppressWarnings({"this-escape"})
  public ServerPreparedStatement(
      String sql,
      Connection con,
      ClosableLock lock,
      boolean canUseServerTimeout,
      boolean canUseServerMaxRows,
      boolean canCachePrepStmts,
      int autoGeneratedKeys,
      int resultSetType,
      int resultSetConcurrency,
      int defaultFetchSize)
      throws SQLException {
    super(
        sql,
        con,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        canCachePrepStmts,
        autoGeneratedKeys,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
    prepareResult = canCachePrepStmts ? con.getContext().getPrepareCacheCmd(sql, this) : null;
    if (prepareResult == null && !PREPARABLE_STATEMENT_PATTERN.matcher(sql).find()) {
      con.getClient().execute(new PreparePacket(sql), this, true);
    }
    parameters = new ParameterList();
  }

  /**
   * Execute command with parameters
   *
   * @throws SQLException if any error occurs
   */
  @SuppressWarnings("try")
  protected void executeInternal() throws SQLException {
    checkNotClosed();
    validParameters();
    try (ClosableLock ignore = lock.closeableLock();
        QueryTimeoutHandler ignore2 = this.con.handleTimeout(queryTimeout)) {
      String cmd = escapeTimeout(sql);
      if (prepareResult == null)
        if (canCachePrepStmts) prepareResult = con.getContext().getPrepareCacheCmd(cmd, this);
      if (prepareResult == null && con.getContext().permitPipeline()) {
        executePipeline(cmd);
      } else {
        executeStandard(cmd);
      }
    } catch (SQLException e) {
      results = null;
      currResult = null;
      throw e;
    } finally {
      localInfileInputStream = null;
    }
  }

  /**
   * Send COM_STMT_PREPARE + COM_STMT_EXECUTE, then read for the 2 answers
   *
   * @param cmd command
   * @throws SQLException if IOException / Command error
   */
  private void executePipeline(String cmd) throws SQLException {
    // server is 10.2+, permitting to execute last prepare with (-1) statement id.
    // Server send prepare, followed by execute, in one exchange.
    try {
      List<Completion> res =
          con.getClient()
              .execute(
                  new PrepareExecutePacket(cmd, parameters, this, localInfileInputStream),
                  this,
                  fetchSize,
                  maxRows,
                  resultSetConcurrency,
                  resultSetType,
                  closeOnCompletion,
                  false);
      results = res.subList(1, res.size());
    } catch (SQLException ex) {
      results = null;
      throw ex;
    }
  }

  private void executeStandard(String cmd) throws SQLException {
    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      if (canCachePrepStmts) prepareResult = con.getContext().getPrepareCacheCmd(cmd, this);
      if (prepareResult == null) {
        con.getClient().execute(new PreparePacket(cmd), this, true);
      }
    }
    validParameters();
    // send COM_STMT_EXECUTE
    ExecutePacket execute =
        new ExecutePacket(prepareResult, parameters, cmd, this, localInfileInputStream);
    results =
        con.getClient()
            .execute(
                execute,
                this,
                fetchSize,
                maxRows,
                resultSetConcurrency,
                resultSetType,
                closeOnCompletion,
                false);
  }

  protected boolean executeInternalPreparedBatch() throws SQLException {
    checkNotClosed();
    String cmd = escapeTimeout(sql);

    // ensure pipelining is possible (no LOAD DATA/XML INFILE commands)
    boolean possibleLoadLocal = con.getContext().hasClientCapability(LOCAL_FILES);
    if (possibleLoadLocal) {
      String sqlUpper = sql.toUpperCase(Locale.ROOT);
      possibleLoadLocal =
          sqlUpper.contains(" LOCAL ") && sqlUpper.contains("LOAD") && sqlUpper.contains(" INFILE");
    }

    if (possibleLoadLocal) {
      executeBatchStandard(cmd);
      return false;
    }

    Configuration conf = con.getContext().getConf();
    parseCommandIfNeeded(sql);

    // a bit complex:
    // * bulk on INSERT ON DUPLICATE KEY UPDATE can only be use if server permit returning
    // individual result, since affected rows cannot be guessed
    // * if RETURN_GENERATED_KEYS is expressly set, can only be use if server permit returning
    // individual result
    boolean canUseBulk =
        con.getContext().hasServerCapability(STMT_BULK_OPERATIONS)
            && (((clientParser.isInsert()
                        && ((conf.useBulkStmts() || conf.useBulkStmtsForInserts())
                            && (con.getContext().hasClientCapability(BULK_UNIT_RESULTS)
                                || !clientParser.isInsertDuplicate())))
                    || (!clientParser.isInsert() && conf.useBulkStmts()))
                && (con.getContext().hasClientCapability(BULK_UNIT_RESULTS)
                    || autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS));
    if (canUseBulk && batchParameters.size() > 1) {
      executeBatchBulk(cmd);
      return true;
    } else {
      executeBatchPipeline(cmd);
      return false;
    }
  }

  /**
   * Send COM_STMT_PREPARE + X * COM_STMT_EXECUTE, then read for the all answers
   *
   * @param cmd command
   * @throws SQLException if Command error
   */
  private void executeBatchPipeline(String cmd) throws SQLException {
    if (prepareResult == null && canCachePrepStmts)
      prepareResult = con.getContext().getPrepareCacheCmd(cmd, this);
    // server is 10.2+, permitting to execute last prepare with (-1) statement id.
    // Server send prepare, followed by execute, in one exchange.
    int maxCmd = 250;
    List<Completion> res = new ArrayList<>();
    try {
      int index = 0;
      if (prepareResult == null) {
        res.addAll(executeBunchPrepare(cmd, index, maxCmd));
        index += maxCmd;
      }
      while (index < batchParameters.size()) {
        res.addAll(executeBunch(cmd, index, maxCmd));
        index += maxCmd;
      }
      results = res;

    } catch (SQLException bue) {
      results = null;
      throw exceptionFactory().createBatchUpdate(res, batchParameters.size(), bue);
    }
  }

  private List<Completion> executeBunch(String cmd, int index, int maxCmd) throws SQLException {
    int maxCmdToSend = Math.min(batchParameters.size() - index, maxCmd);
    ClientMessage[] packets = new ClientMessage[maxCmdToSend];
    for (int i = index; i < index + maxCmdToSend; i++) {
      packets[i - index] =
          new ExecutePacket(
              prepareResult, batchParameters.get(i), cmd, this, localInfileInputStream);
    }
    return con.getClient()
        .executePipeline(
            packets,
            this,
            0,
            maxRows,
            ResultSet.CONCUR_READ_ONLY,
            ResultSet.TYPE_FORWARD_ONLY,
            closeOnCompletion,
            false);
  }

  private List<Completion> executeBunchPrepare(String cmd, int index, int maxCmd)
      throws SQLException {
    int maxCmdToSend = Math.min(batchParameters.size() - index, maxCmd);
    ClientMessage[] packets = new ClientMessage[maxCmdToSend + 1];
    packets[0] = new PreparePacket(cmd);
    for (int i = index; i < index + maxCmdToSend; i++) {
      packets[i + 1 - index] =
          new ExecutePacket(null, batchParameters.get(i), cmd, this, localInfileInputStream);
    }
    List<Completion> res =
        con.getClient()
            .executePipeline(
                packets,
                this,
                0,
                maxRows,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.TYPE_FORWARD_ONLY,
                closeOnCompletion,
                false);
    // in case of failover, prepare is done in failover, skipping prepare result
    if (res.get(0) instanceof PrepareResultPacket) {
      return res.subList(1, res.size());
    } else {
      return res;
    }
  }

  /**
   * Send COM_STMT_PREPARE + read answer, then Send a COM_STMT_EXECUTE + read answer * n time
   *
   * @param cmd command
   * @throws SQLException if IOException / Command error
   */
  private void executeBatchStandard(String cmd) throws SQLException {
    // send COM_STMT_PREPARE
    List<Completion> tmpResults = new ArrayList<>();
    SQLException error = null;
    for (Parameters batchParameter : batchParameters) {
      // prepare is in loop, because if connection fail, prepare is reset, and need to be re
      // prepared
      if (prepareResult == null) {
        if (canCachePrepStmts) prepareResult = con.getContext().getPrepareCacheCmd(cmd, this);
        if (prepareResult == null) {
          con.getClient().execute(new PreparePacket(cmd), this, false);
        }
      }
      try {
        ExecutePacket execute =
            new ExecutePacket(prepareResult, batchParameter, cmd, this, localInfileInputStream);
        tmpResults.addAll(con.getClient().execute(execute, this, false));
      } catch (SQLException e) {
        if (error == null) error = e;
      }
    }

    if (error != null) {
      throw exceptionFactory().createBatchUpdate(tmpResults, batchParameters.size(), error);
    }
    this.results = tmpResults;
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which may be any kind
   * of SQL statement. Some prepared statements return multiple results; the <code>execute</code>
   * method handles these complex statements as well as the simpler form of statements handled by
   * the methods <code>executeQuery</code> and <code>executeUpdate</code>.
   *
   * <p>The <code>execute</code> method returns a <code>boolean</code> to indicate the form of the
   * first result. You must call either the method <code>getResultSet</code> or <code>getUpdateCount
   * </code> to retrieve the result; you must call <code>getMoreResults</code> to move to any
   * subsequent result(s).
   *
   * @return <code>true</code> if the first result is a <code>ResultSet</code> object; <code>false
   *     </code> if the first result is an update count or there is no result
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or an argument is supplied to this method
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @see Statement#execute
   * @see Statement#getResultSet
   * @see Statement#getUpdateCount
   * @see Statement#getMoreResults
   */
  @Override
  public boolean execute() throws SQLException {
    executeInternal();
    handleParameterOutput();
    return currResult instanceof Result;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    super.setMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    super.setLargeMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    super.setQueryTimeout(seconds);
    if (canUseServerTimeout && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  /**
   * Executes the SQL query in this <code>PreparedStatement</code> object and returns the <code>
   * ResultSet</code> object generated by the query.
   *
   * @return a <code>ResultSet</code> object that contains the data produced by the query; never
   *     <code>null</code>
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement does not return a <code>ResultSet
   *     </code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    executeInternal();
    handleParameterOutput();
    if ((currResult instanceof Result)) {
      return (Result) currResult;
    }
    if (Boolean.parseBoolean(
        con.getContext().getConf().nonMappedOptions().getProperty("permitNoResults", "false"))) {
      // for compatibility with pre 3.4.0 version
      return new CompleteResult(
          new ColumnDecoder[0], new byte[0][], con.getContext(), resultSetType);
    }

    throw new SQLException(
        "PrepareStatement.executeQuery() command does NOT return a result-set as expected. Either"
            + " use PrepareStatement.execute(), PrepareStatement.executeUpdate(), or correct"
            + " command");
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public int executeUpdate() throws SQLException {
    return (int) executeLargeUpdate();
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * <p>This method should be used when the returned row count may exceed {@link Integer#MAX_VALUE}.
   *
   * <p>The default implementation will throw {@code UnsupportedOperationException}
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @since 1.8
   */
  @Override
  public long executeLargeUpdate() throws SQLException {
    executeInternal();
    handleParameterOutput();
    if (currResult instanceof Result) {
      throw exceptionFactory()
          .create("the given SQL statement produces an unexpected ResultSet object", "HY000");
    }
    return ((OkPacket) currResult).getAffectedRows();
  }

  /**
   * Handle output parameter result-set (only for CallableStatement)
   *
   * @throws SQLException if any error occurs
   */
  protected void handleParameterOutput() throws SQLException {
    currResult = results.remove(0);
  }

  /**
   * Adds a set of parameters to this <code>PreparedStatement</code> object's batch of commands.
   *
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see Statement#addBatch
   * @since 1.2
   */
  @Override
  public void addBatch() throws SQLException {
    validParameters();
    if (batchParameters == null) batchParameters = new ArrayList<>();
    batchParameters.add(parameters);
    parameters = parameters.clone();
  }

  /**
   * Validated that all parameters have been set.
   *
   * @throws SQLException if number of parameters doesn't correspond to expected number
   */
  protected void validParameters() throws SQLException {
    if (prepareResult != null) {
      for (int i = 0; i < prepareResult.getParameters().length; i++) {
        if (!parameters.containsKey(i)) {
          throw exceptionFactory()
              .create("Parameter at position " + (i + 1) + " is not set", "07004");
        }
      }
    } else {

      if (batchParameters != null
          && !batchParameters.isEmpty()
          && parameters.size() < batchParameters.get(0).size()) {
        // ensure batch parameters set same number
        throw exceptionFactory()
            .create(
                "batch set of parameters differ from previous set. All parameters must be set",
                "07004");
      }

      // ensure all parameters are set
      for (int i = 0; i < parameters.size(); i++) {
        if (!parameters.containsKey(i)) {
          throw exceptionFactory()
              .create("Parameter at position " + (i + 1) + " is not set", "07004");
        }
      }
    }
  }

  /**
   * Retrieves a <code>ResultSetMetaData</code> object that contains information about the columns
   * of the <code>ResultSet</code> object that will be returned when this <code>PreparedStatement
   * </code> object is executed.
   *
   * <p>Because a <code>PreparedStatement</code> object is precompiled, it is possible to know about
   * the <code>ResultSet</code> object that it will return without having to execute it.
   * Consequently, it is possible to invoke the method <code>getMetaData</code> on a <code>
   * PreparedStatement</code> object rather than waiting to execute it and then invoking the <code>
   * ResultSet.getMetaData</code> method on the <code>ResultSet</code> object that is returned.
   *
   * <p><B>NOTE:</B> Using this method may be expensive for some drivers due to the lack of
   * underlying DBMS support.
   *
   * @return the description of a <code>ResultSet</code> object's columns or <code>null</code> if
   *     the driver cannot return a <code>ResultSetMetaData</code> object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
   * @since 1.2
   */
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {

    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this, true);
    }

    return new org.mariadb.jdbc.client.result.ResultSetMetaData(
        exceptionFactory(), prepareResult.getColumns(), con.getContext().getConf(), false);
  }

  /**
   * Retrieves the number, types and properties of this <code>PreparedStatement</code> object's
   * parameters.
   *
   * @return a <code>ParameterMetaData</code> object that contains information about the number,
   *     types and properties for each parameter marker of this <code>PreparedStatement</code>
   *     object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see ParameterMetaData
   * @since 1.4
   */
  @Override
  public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this, true);
    }

    return new ParameterMetaData(exceptionFactory(), prepareResult.getParameters());
  }

  private ExceptionFactory exceptionFactory() {
    return con.getExceptionFactory().of(this);
  }

  @Override
  public void close() throws SQLException {
    if (prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
    con.fireStatementClosed(this);
    super.close();
  }

  @Override
  public String toString() {
    return "ServerPreparedStatement{" + super.toString() + '}';
  }
}
