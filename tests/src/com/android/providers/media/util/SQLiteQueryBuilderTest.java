/*
 * Copyright (C) 2009 The Android Open Source Project
 *
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
 */

package com.android.providers.media.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteCursorDriver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQuery;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

@RunWith(AndroidJUnit4.class)
public class SQLiteQueryBuilderTest {
    private SQLiteDatabase mDatabase;
    private SQLiteQueryBuilder mStrictBuilder;

    private final String TEST_TABLE_NAME = "test";
    private final String EMPLOYEE_TABLE_NAME = "employee";
    private static final String DATABASE_FILE = "database_test.db";

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();

        context.deleteDatabase(DATABASE_FILE);
        mDatabase = Objects.requireNonNull(
                context.openOrCreateDatabase(DATABASE_FILE, Context.MODE_PRIVATE, null));

        createEmployeeTable();
        createStrictQueryBuilder();
    }

    @After
    public void tearDown() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();

        mDatabase.close();
        context.deleteDatabase(DATABASE_FILE);
    }

    @Test
    public void testConstructor() {
        new SQLiteQueryBuilder();
    }

    @Test
    public void testSetDistinct() {
        String expected;
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(TEST_TABLE_NAME);
        sqliteQueryBuilder.setDistinct(false);
        sqliteQueryBuilder.appendWhere("age=20");
        String sql = sqliteQueryBuilder.buildQuery(new String[] { "age", "address" },
                null, null, null, null, null);
        assertEquals(TEST_TABLE_NAME, sqliteQueryBuilder.getTables());
        expected = "SELECT age, address FROM " + TEST_TABLE_NAME + " WHERE (age=20)";
        assertEquals(expected, sql);

        sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(EMPLOYEE_TABLE_NAME);
        sqliteQueryBuilder.setDistinct(true);
        sqliteQueryBuilder.appendWhere("age>32");
        sql = sqliteQueryBuilder.buildQuery(new String[] { "age", "address" },
                null, null, null, null, null);
        assertEquals(EMPLOYEE_TABLE_NAME, sqliteQueryBuilder.getTables());
        expected = "SELECT DISTINCT age, address FROM " + EMPLOYEE_TABLE_NAME + " WHERE (age>32)";
        assertEquals(expected, sql);

        sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(EMPLOYEE_TABLE_NAME);
        sqliteQueryBuilder.setDistinct(true);
        sqliteQueryBuilder.appendWhereEscapeString("age>32");
        sql = sqliteQueryBuilder.buildQuery(new String[] { "age", "address" },
                null, null, null, null, null);
        assertEquals(EMPLOYEE_TABLE_NAME, sqliteQueryBuilder.getTables());
        expected = "SELECT DISTINCT age, address FROM " + EMPLOYEE_TABLE_NAME
                + " WHERE ('age>32')";
        assertEquals(expected, sql);
    }

    @Test
    public void testSetProjectionMap() {
        String expected;
        Map<String, String> projectMap = new HashMap<String, String>();
        projectMap.put("EmployeeName", "name");
        projectMap.put("EmployeeAge", "age");
        projectMap.put("EmployeeAddress", "address");
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(TEST_TABLE_NAME);
        sqliteQueryBuilder.setDistinct(false);
        sqliteQueryBuilder.setProjectionMap(projectMap);
        String sql = sqliteQueryBuilder.buildQuery(new String[] { "EmployeeName", "EmployeeAge" },
                null, null, null, null, null);
        expected = "SELECT name, age FROM " + TEST_TABLE_NAME;
        assertEquals(expected, sql);

        sql = sqliteQueryBuilder.buildQuery(null, // projectionIn is null
                null, null, null, null, null);
        assertTrue(sql.matches("SELECT (age|name|address), (age|name|address), (age|name|address) "
                + "FROM " + TEST_TABLE_NAME));
        assertTrue(sql.contains("age"));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("address"));

        sqliteQueryBuilder.setProjectionMap(null);
        sql = sqliteQueryBuilder.buildQuery(new String[] { "name", "address" },
                null, null, null, null, null);
        assertTrue(sql.matches("SELECT (name|address), (name|address) "
                + "FROM " + TEST_TABLE_NAME));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("address"));
    }

    private static class MockCursor extends SQLiteCursor {
        public MockCursor(SQLiteCursorDriver driver,
                String editTable, SQLiteQuery query) {
            super(driver, editTable, query);
        }
    }

    @Test
    public void testBuildQueryString() {
        String expected;
        final String[] DEFAULT_TEST_PROJECTION = new String [] { "name", "age", "sum(salary)" };
        final String DEFAULT_TEST_WHERE = "age > 25";
        final String DEFAULT_HAVING = "sum(salary) > 3000";

        String sql = SQLiteQueryBuilder.buildQueryString(false, "Employee",
                DEFAULT_TEST_PROJECTION,
                DEFAULT_TEST_WHERE, "name", DEFAULT_HAVING, "name", "100");

        expected = "SELECT name, age, sum(salary) FROM Employee WHERE " + DEFAULT_TEST_WHERE +
                " GROUP BY name " +
                "HAVING " + DEFAULT_HAVING + " " +
                "ORDER BY name " +
                "LIMIT 100";
        assertEquals(expected, sql);
    }

    @Test
    public void testBuildQuery() {
        final String[] DEFAULT_TEST_PROJECTION = new String[] { "name", "sum(salary)" };
        final String DEFAULT_TEST_WHERE = "age > 25";
        final String DEFAULT_HAVING = "sum(salary) > 2000";

        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(TEST_TABLE_NAME);
        sqliteQueryBuilder.setDistinct(false);
        String sql = sqliteQueryBuilder.buildQuery(DEFAULT_TEST_PROJECTION,
                DEFAULT_TEST_WHERE, "name", DEFAULT_HAVING, "name", "2");
        String expected = "SELECT name, sum(salary) FROM " + TEST_TABLE_NAME
                + " WHERE (" + DEFAULT_TEST_WHERE + ") " +
                "GROUP BY name HAVING " + DEFAULT_HAVING + " ORDER BY name LIMIT 2";
        assertEquals(expected, sql);
    }

    @Test
    public void testAppendColumns() {
        StringBuilder sb = new StringBuilder();
        String[] columns = new String[] { "name", "age" };

        assertEquals("", sb.toString());
        SQLiteQueryBuilder.appendColumns(sb, columns);
        assertEquals("name, age ", sb.toString());
    }

    @Test
    public void testAppendWhereStandalone() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("Employee");
        qb.appendWhereStandalone("A");
        qb.appendWhereStandalone("B");
        qb.appendWhereStandalone("C");

        final String query = qb.buildQuery(null, null, null, null, null, null);
        assertTrue(query.contains("(A) AND (B) AND (C)"));
    }

    @Test
    public void testQuery() {
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables("Employee");
        Cursor cursor = sqliteQueryBuilder.query(mDatabase,
                new String[] { "name", "sum(salary)" }, null, null,
                "name", "sum(salary)>1000", "name", null, null);
        assertNotNull(cursor);
        assertEquals(3, cursor.getCount());

        final int COLUMN_NAME_INDEX = 0;
        final int COLUMN_SALARY_INDEX = 1;
        cursor.moveToFirst();
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX));
        assertEquals(4500, cursor.getInt(COLUMN_SALARY_INDEX));
        cursor.moveToNext();
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX));
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX));
        cursor.moveToNext();
        assertEquals("jack", cursor.getString(COLUMN_NAME_INDEX));
        assertEquals(3500, cursor.getInt(COLUMN_SALARY_INDEX));

        sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables(EMPLOYEE_TABLE_NAME);
        cursor = sqliteQueryBuilder.query(mDatabase,
                new String[] { "name", "sum(salary)" }, null, null,
                "name", "sum(salary)>1000", "name", "2" // limit is 2
                , null);
        assertNotNull(cursor);
        assertEquals(2, cursor.getCount());
        cursor.moveToFirst();
        assertEquals("Jim", cursor.getString(COLUMN_NAME_INDEX));
        assertEquals(4500, cursor.getInt(COLUMN_SALARY_INDEX));
        cursor.moveToNext();
        assertEquals("Mike", cursor.getString(COLUMN_NAME_INDEX));
        assertEquals(4000, cursor.getInt(COLUMN_SALARY_INDEX));
    }

    @Test
    public void testCancelableQuery_WhenNotCanceled_ReturnsResultSet() {
        CancellationSignal cancellationSignal = new CancellationSignal();
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables("Employee");
        Cursor cursor = sqliteQueryBuilder.query(mDatabase,
                new String[] { "name", "sum(salary)" }, null, null,
                "name", "sum(salary)>1000", "name", null, cancellationSignal);

        assertEquals(3, cursor.getCount());
    }

    @Test
    public void testCancelableQuery_WhenCanceledBeforeQuery_ThrowsImmediately() {
        CancellationSignal cancellationSignal = new CancellationSignal();
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables("Employee");

        cancellationSignal.cancel();
        try {
            sqliteQueryBuilder.query(mDatabase,
                    new String[] { "name", "sum(salary)" }, null, null,
                    "name", "sum(salary)>1000", "name", null, cancellationSignal);
            fail("Expected OperationCanceledException");
        } catch (OperationCanceledException ex) {
            // expected
        }
    }

    @Test
    public void testCancelableQuery_WhenCanceledAfterQuery_ThrowsWhenExecuted() {
        CancellationSignal cancellationSignal = new CancellationSignal();
        SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
        sqliteQueryBuilder.setTables("Employee");

        Cursor cursor = sqliteQueryBuilder.query(mDatabase,
                new String[] { "name", "sum(salary)" }, null, null,
                "name", "sum(salary)>1000", "name", null, cancellationSignal);

        cancellationSignal.cancel();
        try {
            cursor.getCount(); // force execution
            fail("Expected OperationCanceledException");
        } catch (OperationCanceledException ex) {
            // expected
        }
    }

    @Test
    public void testCancelableQuery_WhenCanceledDueToContention_StopsWaitingAndThrows() {
        for (int i = 0; i < 5; i++) {
            final CancellationSignal cancellationSignal = new CancellationSignal();
            final Semaphore barrier1 = new Semaphore(0);
            final Semaphore barrier2 = new Semaphore(0);
            Thread contentionThread = new Thread() {
                @Override
                public void run() {
                    mDatabase.beginTransaction(); // acquire the only available connection
                    barrier1.release(); // release query to start running
                    try {
                        barrier2.acquire(); // wait for test to end
                    } catch (InterruptedException e) {
                    }
                    mDatabase.endTransaction(); // release the connection
                }
            };
            Thread cancellationThread = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                    }
                    cancellationSignal.cancel();
                }
            };
            try {
                SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
                sqliteQueryBuilder.setTables("Employee");

                contentionThread.start();
                cancellationThread.start();

                try {
                    barrier1.acquire(); // wait for contention thread to start transaction
                } catch (InterruptedException e) {
                }

                final long startTime = System.nanoTime();
                try {
                    Cursor cursor = sqliteQueryBuilder.query(mDatabase,
                            new String[] { "name", "sum(salary)" }, null, null,
                            "name", "sum(salary)>1000", "name", null, cancellationSignal);
                    cursor.getCount(); // force execution
                    fail("Expected OperationCanceledException");
                } catch (OperationCanceledException ex) {
                    // expected
                }

                // We want to confirm that the query really was blocked trying to acquire a
                // connection for a certain amount of time before it was freed by cancel.
                final long waitTime = System.nanoTime() - startTime;
                if (waitTime > 150 * 1000000L) {
                    return; // success!
                }
            } finally {
                barrier1.release();
                barrier2.release();
                try {
                    contentionThread.join();
                    cancellationThread.join();
                } catch (InterruptedException e) {
                }
            }
        }

        // Occasionally we might miss the timing deadline due to factors in the
        // environment, but if after several trials we still couldn't demonstrate
        // that the query was blocked, then the test must be broken.
        fail("Could not prove that the query actually blocked before cancel() was called.");
    }

    @Test
    public void testCancelableQuery_WhenCanceledDuringLongRunningQuery_CancelsQueryAndThrows() {
        // Populate a table with a bunch of integers.
        mDatabase.execSQL("CREATE TABLE x (v INTEGER);");
        for (int i = 0; i < 100; i++) {
            mDatabase.execSQL("INSERT INTO x VALUES (?)", new Object[] { i });
        }

        for (int i = 0; i < 5; i++) {
            final CancellationSignal cancellationSignal = new CancellationSignal();
            Thread cancellationThread = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                    }
                    cancellationSignal.cancel();
                }
            };
            try {
                // Build an unsatisfiable 5-way cross-product query over 100 values but
                // produces no output.  This should force SQLite to loop for a long time
                // as it tests 10^10 combinations.
                SQLiteQueryBuilder sqliteQueryBuilder = new SQLiteQueryBuilder();
                sqliteQueryBuilder.setTables("x AS a, x AS b, x AS c, x AS d, x AS e");

                cancellationThread.start();

                final long startTime = System.nanoTime();
                try {
                    Cursor cursor = sqliteQueryBuilder.query(mDatabase, null,
                            "a.v + b.v + c.v + d.v + e.v > 1000000",
                            null, null, null, null, null, cancellationSignal);
                    cursor.getCount(); // force execution
                    fail("Expected OperationCanceledException");
                } catch (OperationCanceledException ex) {
                    // expected
                }

                // We want to confirm that the query really was running and then got
                // canceled midway.
                final long waitTime = System.nanoTime() - startTime;
                if (waitTime > 150 * 1000000L && waitTime < 600 * 1000000L) {
                    return; // success!
                }
            } finally {
                try {
                    cancellationThread.join();
                } catch (InterruptedException e) {
                }
            }
        }

        // Occasionally we might miss the timing deadline due to factors in the
        // environment, but if after several trials we still couldn't demonstrate
        // that the query was canceled, then the test must be broken.
        fail("Could not prove that the query actually canceled midway during execution.");
    }

    @Test
    public void testUpdate() throws Exception {
        final ContentValues values = new ContentValues();
        values.put("name", "Anonymous");
        values.put("salary", 0);

        {
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("employee");
            qb.appendWhere("month=3");
            assertEquals(2, qb.update(mDatabase, values, null, null));
        }
        {
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("employee");
            assertEquals(1, qb.update(mDatabase, values, "month=?", new String[] { "2" }));
        }
    }

    @Test
    public void testDelete() throws Exception {
        {
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("employee");
            qb.appendWhere("month=3");
            assertEquals(2, qb.delete(mDatabase, null, null));
            assertEquals(0, qb.delete(mDatabase, null, null));
        }
        {
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("employee");
            assertEquals(1, qb.delete(mDatabase, "month=?", new String[] { "2" }));
            assertEquals(0, qb.delete(mDatabase, "month=?", new String[] { "2" }));
        }
    }

    @Test
    public void testStrictQuery() throws Exception {
        final SQLiteQueryBuilder qb = mStrictBuilder;

        // Should normally only be able to see one row
        try (Cursor c = qb.query(mDatabase, null, null, null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
        }

        // Trying sneaky queries should fail; even if they somehow succeed, we
        // shouldn't get to see any other data.
        try (Cursor c = qb.query(mDatabase, null, "1=1", null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
        } catch (Exception tolerated) {
        }
        try (Cursor c = qb.query(mDatabase, null, "1=1 --", null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
        } catch (Exception tolerated) {
        }
        try (Cursor c = qb.query(mDatabase, null, "1=1) OR (1=1", null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
        } catch (Exception tolerated) {
        }
        try (Cursor c = qb.query(mDatabase, null, "1=1)) OR ((1=1", null, null, null, null, null, null)) {
            assertEquals(1, c.getCount());
        } catch (Exception tolerated) {
        }
    }

    @Test
    public void testStrictUpdate() throws Exception {
        final SQLiteQueryBuilder qb = mStrictBuilder;

        final ContentValues values = new ContentValues();
        values.put("name", "Anonymous");

        // Should normally only be able to update one row
        assertEquals(1, qb.update(mDatabase, values, null, null));

        // Trying sneaky queries should fail; even if they somehow succeed, we
        // shouldn't get to see any other data.
        try {
            assertEquals(1, qb.update(mDatabase, values, "1=1", null));
        } catch (Exception tolerated) {
        }
        try {
            assertEquals(1, qb.update(mDatabase, values, "1=1 --", null));
        } catch (Exception tolerated) {
        }
        try {
            assertEquals(1, qb.update(mDatabase, values, "1=1) OR (1=1", null));
        } catch (Exception tolerated) {
        }
        try {
            assertEquals(1, qb.update(mDatabase, values, "1=1)) OR ((1=1", null));
        } catch (Exception tolerated) {
        }
    }

    @Test
    public void testStrictDelete() throws Exception {
        final SQLiteQueryBuilder qb = mStrictBuilder;

        // Should normally only be able to update one row
        createEmployeeTable();
        assertEquals(1, qb.delete(mDatabase, null, null));

        // Trying sneaky queries should fail; even if they somehow succeed, we
        // shouldn't get to see any other data.
        try {
            createEmployeeTable();
            assertEquals(1, qb.delete(mDatabase, "1=1", null));
        } catch (Exception tolerated) {
        }
        try {
            createEmployeeTable();
            assertEquals(1, qb.delete(mDatabase, "1=1 --", null));
        } catch (Exception tolerated) {
        }
        try {
            createEmployeeTable();
            assertEquals(1, qb.delete(mDatabase, "1=1) OR (1=1", null));
        } catch (Exception tolerated) {
        }
        try {
            createEmployeeTable();
            assertEquals(1, qb.delete(mDatabase, "1=1)) OR ((1=1", null));
        } catch (Exception tolerated) {
        }
    }

    private static final String[] COLUMNS_VALID = new String[] {
            "_id",
    };

    private static final String[] COLUMNS_INVALID = new String[] {
            "salary",
            "MAX(salary)",
            "undefined",
            "(secret_column IN secret_table)",
            "(SELECT secret_column FROM secret_table)",
    };

    @Test
    public void testStrictQueryProjection() throws Exception {
        for (String column : COLUMNS_VALID) {
            assertStrictQueryValid(
                    new String[] { column }, null, null, null, null, null, null);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    new String[] { column }, null, null, null, null, null, null);
        }
    }

    @Test
    public void testStrictQueryWhere() throws Exception {
        for (String column : COLUMNS_VALID) {
            assertStrictQueryValid(
                    null, column + ">0", null, null, null, null, null);
            assertStrictQueryValid(
                    null, "_id>" + column, null, null, null, null, null);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    null, column + ">0", null, null, null, null, null);
            assertStrictQueryInvalid(
                    null, "_id>" + column, null, null, null, null, null);
        }
    }

    @Test
    public void testStrictQueryGroupBy() {
        for (String column : COLUMNS_VALID) {
            assertStrictQueryValid(
                    null, null, null, column, null, null, null);
            assertStrictQueryValid(
                    null, null, null, "_id," + column, null, null, null);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    null, null, null, column, null, null, null);
            assertStrictQueryInvalid(
                    null, null, null, "_id," + column, null, null, null);
        }
    }

    @Test
    public void testStrictQueryHaving() {
        for (String column : COLUMNS_VALID) {
            assertStrictQueryValid(
                    null, null, null, "_id", column, null, null);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    null, null, null, "_id", column, null, null);
        }
    }

    @Test
    public void testStrictQueryOrderBy() {
        for (String column : COLUMNS_VALID) {
            assertStrictQueryValid(
                    null, null, null, null, null, column, null);
            assertStrictQueryValid(
                    null, null, null, null, null, column + " ASC", null);
            assertStrictQueryValid(
                    null, null, null, null, null, "_id COLLATE NOCASE ASC," + column, null);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    null, null, null, null, null, column, null);
            assertStrictQueryInvalid(
                    null, null, null, null, null, column + " ASC", null);
            assertStrictQueryInvalid(
                    null, null, null, null, null, "_id COLLATE NOCASE ASC," + column, null);
        }
    }

    @Test
    public void testStrictQueryLimit() {
        assertStrictQueryValid(
                null, null, null, null, null, null, "32");
        assertStrictQueryValid(
                null, null, null, null, null, null, "0,32");
        assertStrictQueryValid(
                null, null, null, null, null, null, "32 OFFSET 0");

        for (String column : COLUMNS_VALID) {
            assertStrictQueryInvalid(
                    null, null, null, null, null, null, column);
        }
        for (String column : COLUMNS_INVALID) {
            assertStrictQueryInvalid(
                    null, null, null, null, null, null, column);
        }
    }

    @Test
    public void testStrictInsertValues() throws Exception {
        final ContentValues values = new ContentValues();
        for (String column : COLUMNS_VALID) {
            values.clear();
            values.put(column, 42);
            assertStrictInsertValid(values);
        }
        for (String column : COLUMNS_INVALID) {
            values.clear();
            values.put(column, 42);
            assertStrictInsertInvalid(values);
        }
    }

    @Test
    public void testStrictUpdateValues() throws Exception {
        final ContentValues values = new ContentValues();
        for (String column : COLUMNS_VALID) {
            values.clear();
            values.put(column, 42);
            assertStrictUpdateValid(values, null, null);
        }
        for (String column : COLUMNS_INVALID) {
            values.clear();
            values.put(column, 42);
            assertStrictUpdateInvalid(values, null, null);
        }
    }

    private void assertStrictInsertValid(ContentValues values) {
        mStrictBuilder.insert(mDatabase, values);
    }

    private void assertStrictInsertInvalid(ContentValues values) {
        try {
            mStrictBuilder.insert(mDatabase, values);
            fail(Arrays.asList(values).toString());
        } catch (Exception expected) {
        }
    }

    private void assertStrictUpdateValid(ContentValues values, String selection,
            String[] selectionArgs) {
        mStrictBuilder.update(mDatabase, values, selection, selectionArgs);
    }

    private void assertStrictUpdateInvalid(ContentValues values, String selection,
            String[] selectionArgs) {
        try {
            mStrictBuilder.update(mDatabase, values, selection, selectionArgs);
            fail(Arrays.asList(values, selection, selectionArgs).toString());
        } catch (Exception expected) {
        }
    }

    private void assertStrictQueryValid(String[] projectionIn, String selection,
            String[] selectionArgs, String groupBy, String having, String sortOrder, String limit) {
        try (Cursor c = mStrictBuilder.query(mDatabase, projectionIn, selection, selectionArgs,
                groupBy, having, sortOrder, limit, null)) {
        }
    }

    private void assertStrictQueryInvalid(String[] projectionIn, String selection,
            String[] selectionArgs, String groupBy, String having, String sortOrder, String limit) {
        try (Cursor c = mStrictBuilder.query(mDatabase, projectionIn, selection, selectionArgs,
                groupBy, having, sortOrder, limit, null)) {
            fail(Arrays.asList(projectionIn, selection, selectionArgs,
                    groupBy, having, sortOrder, limit).toString());
        } catch (Exception expected) {
        }
    }

    private void createEmployeeTable() {
        mDatabase.execSQL("DROP TABLE IF EXISTS employee;");
        mDatabase.execSQL("CREATE TABLE employee (_id INTEGER PRIMARY KEY, " +
                "name TEXT, month INTEGER, salary INTEGER);");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('Mike', '1', '1000');");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('Mike', '2', '3000');");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('jack', '1', '2000');");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('jack', '3', '1500');");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('Jim', '1', '1000');");
        mDatabase.execSQL("INSERT INTO employee (name, month, salary) " +
                "VALUES ('Jim', '3', '3500');");
    }

    private void createStrictQueryBuilder() {
        mStrictBuilder = new SQLiteQueryBuilder();
        mStrictBuilder.setTables("employee");
        mStrictBuilder.setStrict(true);
        mStrictBuilder.setStrictColumns(true);
        mStrictBuilder.setStrictGrammar(true);
        mStrictBuilder.appendWhere("month=2");

        final Map<String, String> projectionMap = new HashMap<>();
        projectionMap.put("_id", "_id");
        projectionMap.put("name", "name");
        projectionMap.put("month", "month");
        mStrictBuilder.setProjectionMap(projectionMap);
    }
}
