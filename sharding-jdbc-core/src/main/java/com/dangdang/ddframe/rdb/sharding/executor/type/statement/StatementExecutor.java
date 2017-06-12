/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.executor.type.statement;

import com.codahale.metrics.Timer.Context;
import com.dangdang.ddframe.rdb.sharding.constant.SQLType;
import com.dangdang.ddframe.rdb.sharding.executor.BaseStatementUnit;
import com.dangdang.ddframe.rdb.sharding.executor.ExecuteUnit;
import com.dangdang.ddframe.rdb.sharding.executor.ExecutorEngine;
import com.dangdang.ddframe.rdb.sharding.executor.event.AbstractExecutionEvent;
import com.dangdang.ddframe.rdb.sharding.executor.event.EventExecutionType;
import com.dangdang.ddframe.rdb.sharding.executor.threadlocal.ExecutorDataMap;
import com.dangdang.ddframe.rdb.sharding.executor.threadlocal.ExecutorExceptionHandler;
import com.dangdang.ddframe.rdb.sharding.executor.type.ExecutorUtils;
import com.dangdang.ddframe.rdb.sharding.metrics.MetricsContext;
import com.dangdang.ddframe.rdb.sharding.util.EventBusInstance;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 多线程执行静态语句对象请求的执行器.
 * 
 * @author gaohongtao
 * @author caohao
 * @author zhangliang
 */
@RequiredArgsConstructor
public final class StatementExecutor {
    
    private final ExecutorEngine executorEngine;
    
    private final SQLType sqlType;
    
    private final Collection<StatementUnit> statementUnits;
    
    /**
     * 执行SQL查询.
     * 
     * @return 结果集列表
     */
    public List<ResultSet> executeQuery() {
        Context context = MetricsContext.start("ShardingStatement-executeQuery");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        List<ResultSet> result;
        try {
            if (1 == statementUnits.size()) {
                StatementUnit statementUnit = statementUnits.iterator().next();
                return Collections.singletonList(executeQuery(statementUnit, isExceptionThrown, dataMap));
            }
            result = executorEngine.execute(statementUnits, new ExecuteUnit<ResultSet>() {
                
                @Override
                public ResultSet execute(final BaseStatementUnit baseStatementUnit) throws Exception {
                    synchronized (baseStatementUnit.getStatement().getConnection()) {
                        return executeQuery(baseStatementUnit, isExceptionThrown, dataMap);
                    }
                }
            });
        } finally {
            MetricsContext.stop(context);
        }
        return result;
    }
    
    private ResultSet executeQuery(final BaseStatementUnit statementUnit, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        ResultSet result;
        ExecutorUtils.setThreadLocalData(isExceptionThrown, dataMap);
        AbstractExecutionEvent event = ExecutorUtils.getExecutionEvent(sqlType, statementUnit.getSqlExecutionUnit());
        EventBusInstance.getInstance().post(event);
        try {
            result = statementUnit.getStatement().executeQuery(statementUnit.getSqlExecutionUnit().getSql());
        } catch (final SQLException ex) {
            ExecutorUtils.handleException(event, ex);
            return null;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        EventBusInstance.getInstance().post(event);
        return result;
    }
    
    /**
     * 执行SQL更新.
     * 
     * @return 更新数量
     */
    public int executeUpdate() {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql);
            }
        });
    }
    
    public int executeUpdate(final int autoGeneratedKeys) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, autoGeneratedKeys);
            }
        });
    }
    
    public int executeUpdate(final int[] columnIndexes) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnIndexes);
            }
        });
    }
    
    public int executeUpdate(final String[] columnNames) {
        return executeUpdate(new Updater() {
            
            @Override
            public int executeUpdate(final Statement statement, final String sql) throws SQLException {
                return statement.executeUpdate(sql, columnNames);
            }
        });
    }
    
    private int executeUpdate(final Updater updater) {
        Context context = MetricsContext.start("ShardingStatement-executeUpdate");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        try {
            if (1 == statementUnits.size()) {
                StatementUnit statementUnit = statementUnits.iterator().next();
                return executeUpdate(updater, statementUnit, isExceptionThrown, dataMap);
            }
            List<Integer> results = executorEngine.execute(statementUnits, new ExecuteUnit<Integer>() {
                
                @Override
                public Integer execute(final BaseStatementUnit baseStatementUnit) throws Exception {
                    synchronized (baseStatementUnit.getStatement().getConnection()) {
                        return executeUpdate(updater, baseStatementUnit, isExceptionThrown, dataMap);
                    }
                }
            });
            return accumulate(results);
        } finally {
            MetricsContext.stop(context);
        }
    }
    
    private int executeUpdate(final Updater updater, final BaseStatementUnit statementUnit, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        int result;
        ExecutorUtils.setThreadLocalData(isExceptionThrown, dataMap);
        AbstractExecutionEvent event = ExecutorUtils.getExecutionEvent(sqlType, statementUnit.getSqlExecutionUnit());
        EventBusInstance.getInstance().post(event);
        try {
            result = updater.executeUpdate(statementUnit.getStatement(), statementUnit.getSqlExecutionUnit().getSql());
        } catch (final SQLException ex) {
            ExecutorUtils.handleException(event, ex);
            return 0;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        EventBusInstance.getInstance().post(event);
        return result;
    }
    
    private int accumulate(final List<Integer> results) {
        int result = 0;
        for (int each : results) {
            result += each;
        }
        return result;
    }
    
    /**
     * 执行SQL请求.
     * 
     * @return true表示执行DQL语句, false表示执行的DML语句
     */
    public boolean execute() {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql);
            }
        });
    }
    
    public boolean execute(final int autoGeneratedKeys) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, autoGeneratedKeys);
            }
        });
    }
    
    public boolean execute(final int[] columnIndexes) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnIndexes);
            }
        });
    }
    
    public boolean execute(final String[] columnNames) {
        return execute(new Executor() {
            
            @Override
            public boolean execute(final Statement statement, final String sql) throws SQLException {
                return statement.execute(sql, columnNames);
            }
        });
    }
    
    private boolean execute(final Executor executor) {
        Context context = MetricsContext.start("ShardingStatement-execute");
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        final Map<String, Object> dataMap = ExecutorDataMap.getDataMap();
        try {
            if (1 == statementUnits.size()) {
                StatementUnit statementUnit = statementUnits.iterator().next();
                return execute(executor, statementUnit, isExceptionThrown, dataMap);
            }
            List<Boolean> result = executorEngine.execute(statementUnits, new ExecuteUnit<Boolean>() {
        
                @Override
                public Boolean execute(final BaseStatementUnit baseStatementUnit) throws Exception {
                    synchronized (baseStatementUnit.getStatement().getConnection()) {
                        return StatementExecutor.this.execute(executor, baseStatementUnit, isExceptionThrown, dataMap);
                    }
                }
            });
            return (null == result || result.isEmpty()) ? false : result.get(0);
        } finally {
            MetricsContext.stop(context);
        }
    }
    
    private boolean execute(final Executor executor, final BaseStatementUnit statementUnit, final boolean isExceptionThrown, final Map<String, Object> dataMap) {
        boolean result;
        ExecutorUtils.setThreadLocalData(isExceptionThrown, dataMap);
        AbstractExecutionEvent event = ExecutorUtils.getExecutionEvent(sqlType, statementUnit.getSqlExecutionUnit());
        EventBusInstance.getInstance().post(event);
        try {
            result = executor.execute(statementUnit.getStatement(), statementUnit.getSqlExecutionUnit().getSql());
        } catch (final SQLException ex) {
            ExecutorUtils.handleException(event, ex);
            return false;
        }
        event.setEventExecutionType(EventExecutionType.EXECUTE_SUCCESS);
        EventBusInstance.getInstance().post(event);
        return result;
    }
    
    private interface Updater {
        
        int executeUpdate(Statement statement, String sql) throws SQLException;
    }
    
    private interface Executor {
        
        boolean execute(Statement statement, String sql) throws SQLException;
    }
}
