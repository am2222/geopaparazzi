/*
 * Geopaparazzi - Digital field mapping on Android based devices
 * Copyright (C) 2016  HydroloGIS (www.hydrologis.com)
 *
 * This program is free software: you can redistribute it and/or modify
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

package eu.geopaparazzi.spatialite.database.spatial;

import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.geopaparazzi.library.GPApplication;
import eu.geopaparazzi.library.core.maps.SpatialiteMap;
import eu.geopaparazzi.library.database.GPLog;
import eu.geopaparazzi.spatialite.database.spatial.core.daos.SPL_Vectors;
import eu.geopaparazzi.spatialite.database.spatial.core.databasehandlers.AbstractSpatialDatabaseHandler;
import eu.geopaparazzi.spatialite.database.spatial.core.databasehandlers.SpatialiteDatabaseHandler;
import eu.geopaparazzi.spatialite.database.spatial.core.enums.GeometryType;
import eu.geopaparazzi.spatialite.database.spatial.core.enums.SpatialDataType;
import eu.geopaparazzi.spatialite.database.spatial.core.enums.VectorLayerQueryModes;
import eu.geopaparazzi.spatialite.database.spatial.core.tables.AbstractSpatialTable;
import eu.geopaparazzi.spatialite.database.spatial.core.tables.SpatialVectorTable;
import eu.geopaparazzi.spatialite.database.spatial.util.SpatialiteLibraryConstants;
import jsqlite.Exception;

/**
 * The base maps sources manager.
 *
 * @author Andrea Antonello (www.hydrologis.com)
 */
public enum SpatialiteSourcesManager {
    INSTANCE;

    private SharedPreferences mPreferences;
    private List<SpatialiteMap> mSpatialiteMaps;

    private HashMap<SpatialiteMap, SpatialVectorTable> mSpatialiteMaps2TablesMap = new HashMap<>();
    private HashMap<SpatialiteMap, SpatialiteDatabaseHandler> mSpatialiteMaps2DbHandlersMap = new HashMap<>();

    private boolean mReReadBasemaps = true;

    SpatialiteSourcesManager() {
        try {
            GPApplication gpApplication = GPApplication.getInstance();
            mPreferences = PreferenceManager.getDefaultSharedPreferences(gpApplication);

            boolean doSpatialiteRecoveryMode = mPreferences.getBoolean(SpatialiteLibraryConstants.PREFS_KEY_SPATIALITE_RECOVERY_MODE,
                    false);
            // doSpatialiteRecoveryMode=true;
            if (doSpatialiteRecoveryMode) {
                // Turn on Spatialite Recovery Modus
                SPL_Vectors.VECTORLAYER_QUERYMODE = VectorLayerQueryModes.CORRECTIVEWITHINDEX;
                // and reset it in the preferences
                Editor editor = mPreferences.edit();
                editor.putBoolean(SpatialiteLibraryConstants.PREFS_KEY_SPATIALITE_RECOVERY_MODE, false);
                editor.apply();
            }
        } catch (java.lang.Exception e) {
            GPLog.error(this, null, e);
        }

    }

    /**
     * Getter for the current available spatialite maps.
     *
     * @return the list of spatialite maps.
     */
    public List<SpatialiteMap> getSpatialiteMaps() {
        try {
            if (mSpatialiteMaps == null || mReReadBasemaps) {
                getSpatialiteMapsFromPreferences();
                mReReadBasemaps = false;
            }
            return mSpatialiteMaps;
        } catch (java.lang.Exception e) {
            GPLog.error(this, null, e);
        }
        return Collections.emptyList();
    }

    /**
     * Reads the maps from preferences and extracts the tables necessary.
     *
     * @throws java.lang.Exception
     */
    private void getSpatialiteMapsFromPreferences() throws java.lang.Exception {
        mSpatialiteMaps2TablesMap.clear();
        clearHandlers();

        String baseMapsJson = mPreferences.getString(SpatialiteMap.SPATIALITEMAPS_PREF_KEY, "");
        List<SpatialiteMap> spatialiteMaps = SpatialiteMap.fromJsonString(baseMapsJson);

        connectSpatialiteMaps(spatialiteMaps);
    }

    private void clearHandlers() {
        // close all databases
        for (Map.Entry<SpatialiteMap, SpatialiteDatabaseHandler> entry : mSpatialiteMaps2DbHandlersMap.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                GPLog.error(this, null, e);
            }
        }
        mSpatialiteMaps2DbHandlersMap.clear();
    }

    /**
     * Save the given list of SpatialiteMaps to the json preferences.
     *
     * @param spatialiteMaps
     * @throws JSONException
     */
    public void saveSpatialiteMapsToPreferences(List<SpatialiteMap> spatialiteMaps) throws JSONException {
        String spatialiteMapJson = SpatialiteMap.toJsonString(spatialiteMaps);
        Editor editor = mPreferences.edit();
        editor.putString(SpatialiteMap.SPATIALITEMAPS_PREF_KEY, spatialiteMapJson);
        editor.apply();
    }


    /**
     * Save the current maps in memory to preferences.
     */
    public void saveCurrentSpatialiteMapsToPreferences() {
        try {
            saveSpatialiteMapsToPreferences(getSpatialiteMaps());
        } catch (JSONException e) {
            GPLog.error(this, null, e);
        }
    }

    /**
     * Adds all SpatialiteMaps contained in the database in the given path.
     * <p/>
     * <p>SpatialiteMaps and database tables/handlers are added to the lists/maps of this manager.</p>
     *
     * @param file
     * @return true, if at least one supported table was found.
     */
    public boolean addSpatialiteMapFromFile(File file) {
        boolean foundSpatialiteMap = false;
        try {
            foundSpatialiteMap = collectTablesFromFile(file);
            saveSpatialiteMapsToPreferences(mSpatialiteMaps);
        } catch (java.lang.Exception e) {
            GPLog.error(this, null, e);
        }
        return foundSpatialiteMap;
    }

    /**
     * Remove a SpatialiteMap and save to preferences.
     * <p/>
     * <p>The SpatialiteMap related tables and handlers are also removed and the connection to
     * the database is closed.</p>
     *
     * @param spatialiteMap
     * @throws java.lang.Exception
     */
    public void removeSpatialiteMap(SpatialiteMap spatialiteMap) throws java.lang.Exception {
        mSpatialiteMaps.remove(spatialiteMap);
        mSpatialiteMaps2TablesMap.remove(spatialiteMap);
        SpatialiteDatabaseHandler handler = mSpatialiteMaps2DbHandlersMap.remove(spatialiteMap);
        if (handler != null) {
            handler.close();
        }
        saveSpatialiteMapsToPreferences(mSpatialiteMaps);
    }

    /**
     * Remove a list of SpatialiteMap and save to preferences.
     * <p/>
     * <p>The SpatialiteMap related tables and handlers are also removed and the connections to
     * the databases are closed.</p>
     *
     * @param spatialiteMaps
     * @throws java.lang.Exception
     */
    public void removeSpatialiteMaps(List<SpatialiteMap> spatialiteMaps) throws java.lang.Exception {
        mSpatialiteMaps.removeAll(spatialiteMaps);
        for (SpatialiteMap spatialiteMap : spatialiteMaps) {
            mSpatialiteMaps2TablesMap.remove(spatialiteMap);
            SpatialiteDatabaseHandler handler = mSpatialiteMaps2DbHandlersMap.remove(spatialiteMap);
            if (handler != null) {
                handler.close();
            }
        }
        saveSpatialiteMapsToPreferences(mSpatialiteMaps);
    }

    /**
     * Collects all the tables from the given file and adds them to the current list/maps of tables.
     * <p>
     * <p>This creates default SpatialiteMaps objects for each table.</p>
     *
     * @param file
     * @return true is at leats one supported table was found.
     * @throws Exception
     */
    @NonNull
    private boolean collectTablesFromFile(File file) throws java.lang.Exception {
        if (mSpatialiteMaps == null) mSpatialiteMaps = new ArrayList<>();
        /*
         * SPATIALITE TABLES
         */
        boolean foundTables = false;
        SpatialiteDatabaseHandler sdbHandler = getVectorHandlerForFile(file);
        if (sdbHandler != null) {
            List<SpatialVectorTable> tables = sdbHandler.getSpatialVectorTables(false);
            for (SpatialVectorTable table : tables) {
                SpatialiteMap tmpSpatialiteMap = table2BaseMap(table);
                if (!mSpatialiteMaps2TablesMap.containsKey(tmpSpatialiteMap)) {
                    mSpatialiteMaps.add(tmpSpatialiteMap);
                    mSpatialiteMaps2TablesMap.put(tmpSpatialiteMap, table);
                    mSpatialiteMaps2DbHandlersMap.put(tmpSpatialiteMap, sdbHandler);
                    foundTables = true;
                }
            }
        }
        return foundTables;
    }

    /**
     * Collects tables and handlers for a list of SpatialiteMaps objects, that were
     * not yet connected to their databases.
     *
     * @param spatialiteMaps the list of maps to create database links for.
     * @return true if tables were found.
     * @throws java.lang.Exception
     */
    private boolean connectSpatialiteMaps(List<SpatialiteMap> spatialiteMaps) throws java.lang.Exception {

        HashMap<String, HashMap<String, SpatialiteMap>> db2Title2Maps = new HashMap<>();
        for (SpatialiteMap spatialiteMap : spatialiteMaps) {
            HashMap<String, SpatialiteMap> tmpMaps = db2Title2Maps.get(spatialiteMap.databasePath);
            if (tmpMaps == null) {
                tmpMaps = new HashMap<>();
                db2Title2Maps.put(spatialiteMap.databasePath, tmpMaps);
            }
            tmpMaps.put(spatialiteMap.title, spatialiteMap);
        }


        if (mSpatialiteMaps == null) mSpatialiteMaps = new ArrayList<>();
        /*
         * SPATIALITE TABLES
         */
        boolean foundTables = false;

        for (Map.Entry<String, HashMap<String, SpatialiteMap>> entry : db2Title2Maps.entrySet()) {
            SpatialiteDatabaseHandler sdbHandler = getVectorHandlerForFile(new File(entry.getKey()));
            if (sdbHandler != null) {
                HashMap<String, SpatialiteMap> maps = entry.getValue();

                List<SpatialVectorTable> tables = sdbHandler.getSpatialVectorTables(false);
                for (SpatialVectorTable table : tables) {
                    String tableTitle = table.getTitle();
                    SpatialiteMap spatialiteMap = maps.get(tableTitle);
                    if (spatialiteMap != null && !mSpatialiteMaps2TablesMap.containsKey(spatialiteMap)) {
                        mSpatialiteMaps.add(spatialiteMap);
                        mSpatialiteMaps2TablesMap.put(spatialiteMap, table);
                        mSpatialiteMaps2DbHandlersMap.put(spatialiteMap, sdbHandler);
                        foundTables = true;
                    }
                }

            }
        }

        return foundTables;
    }

    /**
     * Create a vector handler for the given file.
     *
     * @param file the file.
     * @return the handler or null if the file is not supported.
     */
    public SpatialiteDatabaseHandler getVectorHandlerForFile(File file) throws IOException {
        if (file.exists() && file.isFile()) {
            String name = file.getName();
            for (SpatialDataType spatialiteType : SpatialDataType.values()) {
                if (!spatialiteType.isSpatialiteBased()) {
                    continue;
                }
                String extension = spatialiteType.getExtension();
                if (name.endsWith(extension)) {
                    SpatialiteDatabaseHandler sdb = new SpatialiteDatabaseHandler(file.getAbsolutePath());
                    if (sdb.isValid()) {
                        return sdb;
                    }
                }
            }
        }
        return null;
    }

//    private void saveToSpatialiteMap(List<SpatialVectorTable> tablesList) throws JSONException {
//        for (SpatialVectorTable table : tablesList) {
//            SpatialiteMap newSpatialiteMap = table2BaseMap(table);
//            mSpatialiteMaps.add(newSpatialiteMap);
//            mSpatialiteMaps2TablesMap.put(newSpatialiteMap, table);
//        }
//        saveSpatialiteMapsToPreferences(mSpatialiteMaps);
//    }


    public HashMap<SpatialiteMap, SpatialVectorTable> getSpatialiteMaps2TablesMap() {
        getSpatialiteMaps();
        return mSpatialiteMaps2TablesMap;
    }

    public HashMap<SpatialiteMap, SpatialiteDatabaseHandler> getSpatialiteMaps2DbHandlersMap() {
        getSpatialiteMaps();
        return mSpatialiteMaps2DbHandlersMap;
    }

    @NonNull
    private SpatialiteMap table2BaseMap(AbstractSpatialTable table) {
        SpatialiteMap newSpatialiteMap = new SpatialiteMap();
        newSpatialiteMap.databasePath = table.getDatabasePath();
        newSpatialiteMap.title = table.getTitle();
        if (table instanceof SpatialVectorTable) {
            try {
                SpatialVectorTable vectorTable = (SpatialVectorTable) table;
                newSpatialiteMap.tableType = vectorTable.getTableTypeDescription();
                GeometryType TYPE = GeometryType.forValue(vectorTable.getGeomType());
                newSpatialiteMap.geometryType = TYPE.getDescription();
            } catch (java.lang.Exception e) {
                GPLog.error(this, null, e);
            }
        }
        return newSpatialiteMap;
    }
}