/**
 * 4DFLib
 * Copyright (c) 2015 Brian Gormanly
 * 4dflib.com
 *
 * 4DFLib is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.fdflib.service.impl;

import com.fdflib.model.entity.FdfEntity;
import com.fdflib.model.state.CommonState;
import com.fdflib.model.util.WhereClause;
import com.fdflib.persistence.FdfPersistence;
import com.fdflib.util.GeneralConstants;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Universal implementation of the 4DF API, allows querying across all Entity states that extend CommonState.
 *
 * Created on 6/10/15.
 * @author brian.gormanly
 */
@SuppressWarnings("unused")
public interface FdfCommonServices {

    static org.slf4j.Logger fdfLog = LoggerFactory.getLogger(CommonState.class);


    /**
     * Save an Entities State to persistence internally manages all insert, update and actions associated with
     * maintaining the correct state of the data in persistence.  Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState State Type to save
     * @param state state to save
     * @param userId Id of user that is saving the state
     * @param systemId Id of system that is saving the state
     * @param <S> parameterized type of entity state
     */
    default <S extends CommonState> FdfEntity<S> save(Class<S> entityState, S state, long userId, long systemId) {

        return save(entityState, state, userId, systemId, 1);
    }


    /**
     * Save an Entities State to persistence internally manages all insert, update and actions associated with
     * maintaining the correct state of the data in persistence.  Includes specified tenant (when using multi-tenant)
     *
     * @param entityState State Type to save
     * @param state state to save
     * @param userId Id of user that is saving the state
     * @param systemId Id of system that is saving the state
     * @param tenantId Id of tenant this entity is associated with
     * @param <S> parameterized type of entity state
     * @return
     */
    default <S extends CommonState> FdfEntity<S> save(Class<S> entityState, S state,
                                                      long userId, long systemId, long tenantId) {
        // set the common meta fields for the new record
        state.arsd = Calendar.getInstance().getTime();
        state.ared = null;
        state.cf = true;
        state.df = false;
        state.euid = userId;
        state.esid = systemId;
        state.tid = tenantId;

        // check to see if this if an id is assigned (existing vs new entity)
        if(state.id <= 0) {
            // if this is a new entity, get an id for it
            state.id = getNewEnityId(entityState);
        }

        // get full entity for state
        FdfEntity<S> thisEntity = getEntityById(entityState, state.id);

        // check to see if there is an existing entity, if not, create
        if(thisEntity == null) {
            thisEntity = new FdfEntity<>();
        }

        // get the previous current record and move to history
        if(thisEntity.current != null) {

            S lastCurrentState = thisEntity.current;

            // set the end date
            lastCurrentState.ared = Calendar.getInstance().getTime();

            // set the current flag
            lastCurrentState.cf = false;

            // move the state to history
            FdfPersistence.getInstance().update(entityState, lastCurrentState);

        }

        // save the new state as current
        long returnedRid = FdfPersistence.getInstance().insert(entityState, state);

        // get the entitiy and return
        return getEntityByRid(entityState, returnedRid);
    }


    /**
     * Sets the delete flag on an entity state. If the state's df flag is set all other queries should by default
     * ignore the deleted state unless the query is specifically designed to retrieve all data including states with
     * the df flag set, usually this would be done for auditing purposes.
     *
     * Setting the df flag might require changes to the ared or arsd of prior or post states to fill the time gap left
     * by the state marked deleted.  All of that logic is handled as part of this method.
     *
     * @param entityState The entity type to query
     * @param state state to set the df flag for
     * @param <S> The parameterized type of the entity
     */
    default <S extends CommonState> void setDeleteFlagSingleState(Class<S> entityState, S state) {
        if(entityState != null && state != null) {

            // set the delete flag for the state
            state.df = true;

            // get full entity for state
            FdfEntity<S> thisEntity = getEntityById(entityState, state.id);

            // find out if this it the current entity
            if(thisEntity.current.rid == state.rid) {

                // since this was a current record set the ared to the current date
                state.ared = Calendar.getInstance().getTime();
                state.cf = false;

                // we just df the current entity so we have to see if there is history and promote the most recent
                if(thisEntity.history.size() > 0) {

                    // find the historical entry with the most recent endDate
                    S lastState = null;
                    for(S historicalState: thisEntity.history) {
                        if(lastState == null) {
                            lastState = historicalState;
                        }
                        else {
                            // if the state ared we are looking at is greater then then the last one make it the last
                            if(historicalState.ared.getTime() > lastState.ared.getTime()) {
                                // the state being looked at is more recent
                                lastState = historicalState;

                            }
                        }
                    }

                    //promote the historical state to current
                    lastState.cf = true;
                    lastState.ared = null;

                    FdfPersistence.getInstance().update(entityState, lastState);
                }
            }

            // save the df state
            FdfPersistence.getInstance().update(entityState, state);

        }
    }

    /**
     * Removes a delete flag from the state passed.  Restores the state to current if appropriate and adjusts others
     * states affected by the change if necessary.
     *
     * @param entityState
     * @param state
     * @param <S>
     */
    default <S extends CommonState> void removeDeleteFlagSingleState(Class<S> entityState, S state) {
        if(entityState != null && state != null) {

            // get full entity for state
            FdfEntity<S> thisEntity = getEntityById(entityState, state.id);

            // check to see if there is no current row, if not, then promote deleted row back to current
            if(thisEntity.current == null) {
                state.df = false;
                state.cf = true;
                state.ared = null;

                // update the df row
                FdfPersistence.getInstance().update(entityState, state);
            }
            else {
                // there was a current row, check to see if the deleted row had the greatest arsd, if it did it should
                // be promoted back to the current row.
                if(thisEntity.current.arsd.getTime() < state.arsd.getTime()) {
                    state.df = false;
                    state.cf = true;
                    state.ared = null;

                    // demote the current, current row
                    thisEntity.current.cf = false;
                    thisEntity.current.ared = state.arsd;

                    FdfPersistence.getInstance().update(entityState, thisEntity.current);

                    // update the df row
                    FdfPersistence.getInstance().update(entityState, state);
                }
                else {
                    // the arsd does not indicate the deleted row should be current, just remove deleted status
                    state.df = false;

                    // update the df row
                    FdfPersistence.getInstance().update(entityState, state);
                }
            }
        }
    }


    /**
     * Retrieves all entities of type passed from persistence. Includes all current and historical data for
     * each entity returned.  Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAll(Class<S> entityState) {

        return getAll(entityState, 1);

    }

    /**
     * Retrieves all entities of type passed from persistence. Includes all current and historical data for
     * each entity returned.  Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAll(Class<S> entityState, long tenantId) {

        // create the where statement for the query
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntities(returnedStates);

    }

    /**
     * Retrieves all entities of type passed from persistence, only returns current data for each entity, without
     * any historical data.  Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllCurrent(Class<S> entityState) {

        return getAllCurrent(entityState, 1);

    }

    /**
     * Retrieves all entities of type passed from persistence, only returns current data for each entity, without
     * any historical data.  Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllCurrent(Class<S> entityState, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that only the current records are returned
        WhereClause whereCf = new WhereClause();
        whereCf.name = "cf";
        whereCf.value = "true";
        whereCf.valueDataType = Boolean.class;

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereCf);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);

    }


    /**
     * Retrieves all entities of type passed from persistence, only returning the historical data in the entity,
     * no current data is included.  Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllHistory(Class<S> entityState) {

        return getAllHistory(entityState, 1);

    }


    /**
     * Retrieves all entities of type passed from persistence, only returning the historical data in the entity,
     * no current data is included.  Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllHistory(Class<S> entityState, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that no current records are returned, just historical ones.
        WhereClause whereCf = new WhereClause();
        whereCf.name = "cf";
        whereCf.value = "false";
        whereCf.valueDataType = Boolean.class;

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereCf);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);
    }


    /**
     * Retrieves all entities of the passed type from persistence as they existed at the date passed. Only states
     * existing at the date passed will be returned.  Usually this will only return one State per Entity in the form
     * they were in at that time, however if the time passed was the same time as a change to a entity you will get
     * back both the states as the end date of the outgoing and the startdate of the incoming will match the date
     * passed.
     *
     * One state will be returned for each entity, if the state is still the current state it will be contained
     * in the entity.current else it will be in the entity.history
     *
     * Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date at which to get entities state
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllAtDate(Class<S> entityState, Date date) {

        return getAllAtDate(entityState, date, 1);

    }


    /**
     * Retrieves all entities of the passed type from persistence as they existed at the date passed. Only states
     * existing at the date passed will be returned.  Usually this will only return one State per Entity in the form
     * they were in at that time, however if the time passed was the same time as a change to a entity you will get
     * back both the states as the end date of the outgoing and the startdate of the incoming will match the date
     * passed.
     *
     * One state will be returned for each entity, if the state is still the current state it will be contained
     * in the entity.current else it will be in the entity.history
     *
     * Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date at which to get entities state
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllAtDate(Class<S> entityState, Date date, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that the start date is less than or equal to the date passed
        WhereClause startDate = new WhereClause();
        startDate.name = "arsd";
        startDate.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        startDate.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        startDate.valueDataType = Date.class;

        // check that the end date is greater than the date passed
        WhereClause endDate = new WhereClause();
        endDate.groupings.add(WhereClause.GROUPINGS.OPEN_PARENTHESIS);
        endDate.name = "ared";
        endDate.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate.valueDataType = Date.class;

        // OR that the end date is null (still active)
        WhereClause endDateNull = new WhereClause();
        endDateNull.conditional = WhereClause.CONDITIONALS.OR;
        endDateNull.name = "ared";
        endDateNull.operator = WhereClause.Operators.IS;
        endDateNull.value = WhereClause.NULL;
        endDateNull.groupings.add(WhereClause.GROUPINGS.CLOSE_PARENTHESIS);

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(startDate);
        whereStatement.add(endDate);
        whereStatement.add(endDateNull);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);
    }


    /**
     * Retrieves all entities that have states active starting at or after the date passed into the method.  Will
     * return current and historical data for the entity equal to or newer then the passed date, but no history
     * with an end date before the passed date.  If a entity does not have a record with a end date equal to or
     * past the passed date, nothing will be returned for that entity.
     *
     * Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date to get entities states from
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllFromDate(Class<S> entityState, Date date) {

        return getAllFromDate(entityState, date, 1);

    }


    /**
     * Retrieves all entities that have states active starting at or after the date passed into the method.  Will
     * return current and historical data for the entity equal to or newer then the passed date, but no history
     * with an end date before the passed date.  If a entity does not have a record with a end date equal to or
     * past the passed date, nothing will be returned for that entity.
     *
     * Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date to get entities states from
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllFromDate(Class<S> entityState, Date date, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that the end date is greater than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.name = "ared";
        endDate1.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate1.valueDataType = Date.class;

        // or that the end date is null (still active)
        WhereClause enddate2 = new WhereClause();
        enddate2.conditional = WhereClause.CONDITIONALS.OR;
        enddate2.name = "ared";
        enddate2.operator = WhereClause.Operators.IS;
        enddate2.value = WhereClause.NULL;

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(endDate1);
        whereStatement.add(enddate2);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);
    }


    /**
     * Retrieves all entities that have states active starting at or before at the date passed into the method.
     * Will return current and historical data for the entity existing prior to the passed date, but no history
     * with an beginning date after the passed date.  If a entity does not have a record with a beginning date
     * equal to or before the passed date, nothing will be returned for that entity.
     *
     * Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date to get entities states before
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllBeforeDate(Class<S> entityState, Date date) {

        return getAllBeforeDate(entityState, date, 1);

    }


    /**
     * Retrieves all entities that have states active starting at or before at the date passed into the method.
     * Will return current and historical data for the entity existing prior to the passed date, but no history
     * with an beginning date after the passed date.  If a entity does not have a record with a beginning date
     * equal to or before the passed date, nothing will be returned for that entity.
     *
     * Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param date Date to get entities states before
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllBeforeDate(Class<S> entityState,
                                                                        Date date, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that the start date is less than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.name = "arsd";
        endDate1.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate1.valueDataType = Date.class;

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(endDate1);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);
    }

    /**
     * Retrieves all entities that have states active starting at or before at the startDate passed into the method
     * and ending at or after the endDate passed. Will return current and historical data for the entity existing
     * between these dates, but no history that exists completely before or after the range.  If an entity has states
     * that exist both inside and outside the range, only the ones existing within the range will be returned.  If a
     * entity has no states existing in the range then that entity will not be returned.
     *
     * Uses the Default FdfTenant (when not using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param startDate Starting date in range to return entity state in
     * @param endDate Ending date in range to return entity state in
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllBetweenDates(Class<S> entityState,
                                                                         Date startDate, Date endDate) {

        return getAllBetweenDates(entityState, startDate, endDate, 1);

    }


    /**
     * Retrieves all entities that have states active starting at or before at the startDate passed into the method
     * and ending at or after the endDate passed. Will return current and historical data for the entity existing
     * between these dates, but no history that exists completely before or after the range.  If an entity has states
     * that exist both inside and outside the range, only the ones existing within the range will be returned.  If a
     * entity has no states existing in the range then that entity will not be returned.
     *
     * Includes specified tenant (when using multi-tenant)
     *
     * @param entityState The entity type to query
     * @param startDate Starting date in range to return entity state in
     * @param endDate Ending date in range to return entity state in
     * @param tenantId Id of the tenant to retrieve for (Multi-FdfTenant mode)
     * @param <S> parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> getAllBetweenDates(Class<S> entityState,
                                                                          Date startDate, Date endDate, long tenantId) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that the start date is less than or equal to the passed date
        WhereClause startDate1 = new WhereClause();
        startDate1.name = "arsd";
        startDate1.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        startDate1.value = GeneralConstants.DB_DATE_FORMAT.format(endDate);
        startDate1.valueDataType = Date.class;

        // check that the end date is greater than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.conditional = WhereClause.CONDITIONALS.AND;
        endDate1.groupings.add(WhereClause.GROUPINGS.OPEN_PARENTHESIS);
        endDate1.name = "ared";
        endDate1.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(startDate);
        endDate1.valueDataType = Date.class;

        // or that the end date is null (still active)
        WhereClause enddate2 = new WhereClause();
        enddate2.conditional = WhereClause.CONDITIONALS.OR;
        enddate2.name = "ared";
        enddate2.operator = WhereClause.Operators.IS;
        enddate2.value = WhereClause.NULL;
        enddate2.groupings.add(WhereClause.GROUPINGS.CLOSE_PARENTHESIS);

        WhereClause whereTid = new WhereClause();
        whereTid.name = "tid";
        whereTid.operator = WhereClause.Operators.EQUAL;
        whereTid.value = Long.toString(tenantId);
        whereTid.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(startDate1);
        whereStatement.add(endDate1);
        whereStatement.add(enddate2);
        whereStatement.add(whereTid);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // organize the results
        return manageReturnedEntities(returnedStates);
    }


    /**
     * Retrieves the entity associated with the id passed. Returns current and historical states for the entity
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param id The Id of the Entity to retrieve
     * @param <S> Parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityById(Class<S> entityState, long id) {

        // create the where statement for the query
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereId);

        // do the query
        List<S> returnedStates =
                FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);

    }

    /**
     * Retrieves the entity associated with the rid passed. Returns current and historical states for the entity
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param rid The Id of the Entity to retrieve
     * @param <S> Parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityByRid(Class<S> entityState, long rid) {

        // create the where statement for the query
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "rid";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(rid);
        whereId.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereId);

        // do the query
        List<S> returnedStates =
                FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // now that we have the state with the rid, get all of the states with the id it contains
        if(returnedStates.get(0) != null) {
            return getEntityById(entityState, returnedStates.get(0).id);
        }
        else {
            return null;
        }
    }

    /**
     * Retrieves the entity of type passed from persistence, only returns current data, without any historical
     * data.
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param <S> parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityCurrentById
        (Class<S> entityState, long id) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that only the current records are returned
        WhereClause whereCf = new WhereClause();
        whereCf.name = "cf";
        whereCf.value = "true";
        whereCf.valueDataType = Boolean.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereCf);
        whereStatement.add(whereId);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);

    }

    /**
     * Retrieves entity of type passed from persistence, only returning the historical data in the entity,
     * no current data is included.
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param <S> parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityHistoryById(Class<S> entityState, long id) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // check that no current records are returned, just historical ones.
        WhereClause whereCf = new WhereClause();
        whereCf.name = "cf";
        whereCf.value = "false";
        whereCf.valueDataType = Boolean.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereCf);
        whereStatement.add(whereId);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);
    }

    /**
     * Retrieves entity of the passed type from persistence as it existed at the date passed. Only states
     * existing at the date passed will be returned.  Usually this will only return one State in the form
     * they were in at that time, however if the time passed was the same time as a change to a entity you will get
     * back both the states as the end date of the outgoing and the startdate of the incoming will match the date
     * passed.
     *
     * If the state is still the current state it will be contained in the entity.current else it will be
     * in the entity.history
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param date Date to get entity state at
     * @param <S> parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityAtDateById(Class<S> entityState, long id, Date date) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        // check that the start date is less than or equal to the date passed
        WhereClause startDate = new WhereClause();
        startDate.name = "arsd";
        startDate.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        startDate.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        startDate.valueDataType = Date.class;

        // check that the end date is greater than the date passed
        WhereClause endDate = new WhereClause();
        endDate.groupings.add(WhereClause.GROUPINGS.OPEN_PARENTHESIS);
        endDate.name = "ared";
        endDate.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate.valueDataType = Date.class;

        // OR that the end date is null (still active)
        WhereClause endDateNull = new WhereClause();
        endDateNull.conditional = WhereClause.CONDITIONALS.OR;
        endDateNull.name = "ared";
        endDateNull.operator = WhereClause.Operators.IS;
        endDateNull.value = WhereClause.NULL;
        endDateNull.groupings.add(WhereClause.GROUPINGS.CLOSE_PARENTHESIS);

        whereStatement.add(whereDf);
        whereStatement.add(whereId);
        whereStatement.add(startDate);
        whereStatement.add(endDate);
        whereStatement.add(endDateNull);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);
    }

    /**
     * Retrieves states active starting at or after the date passed into the method for the entity requested.  Will
     * return current and historical data for the entity equal to or newer then the passed date, but no history
     * with an end date before the passed date.  If a entity does not have a record with a end date equal to or
     * past the passed date, nothing will be returned for that entity.
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param date Date to get entity state(s) from
     * @param <S> parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityFromDateById(Class<S> entityState, long id, Date date) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        // check that the end date is greater than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.name = "ared";
        endDate1.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate1.valueDataType = Date.class;

        // or that the end date is null (still active)
        WhereClause enddate2 = new WhereClause();
        enddate2.conditional = WhereClause.CONDITIONALS.OR;
        enddate2.name = "ared";
        enddate2.operator = WhereClause.Operators.IS;
        enddate2.value = WhereClause.NULL;

        whereStatement.add(whereDf);
        whereStatement.add(whereId);
        whereStatement.add(endDate1);
        whereStatement.add(enddate2);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);
    }

    /**
     * Retrieves entity that has states active starting at or before at the date passed into the method.
     * Will return current and historical data for the entity existing prior to the passed date, but no history
     * with an beginning date after the passed date.  If a entity does not have a record with a beginning date
     * equal to or before the passed date, nothing will be returned for that entity.
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param date Date to get entity state(s) before
     * @param <S> parameterized type of entity
     * @return Entity of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityBeforeDateById(Class<S> entityState, long id, Date date) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        // check that the start date is less than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.name = "arsd";
        endDate1.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(date);
        endDate1.valueDataType = Date.class;

        whereStatement.add(whereDf);
        whereStatement.add(whereId);
        whereStatement.add(endDate1);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);
    }

    /**
     * Retrieves entity that has states active starting at or before at the startDate passed into the method
     * and ending at or after the endDate passed. Will return current and historical data for the entity existing
     * between these dates, but no history that exists completely before or after the range.  If an entity has states
     * that exist both inside and outside the range, only the ones existing within the range will be returned.  If a
     * entity has no states existing in the range then that entity will not be returned.
     *
     * Getting an entity by Id is a "FdfTenant Safe" operation and works for multi and single tenant calls.
     *
     * @param entityState The entity type to query
     * @param startDate Start date in range to get entity state(s) within
     * @param endDate End date in range to get entity state(s) within
     * @param <S> Parameterized type of entity
     * @return List of type passed
     */
    default <S extends CommonState> FdfEntity<S> getEntityBetweenDatesById(Class<S> entityState, long id,
                                                                      Date startDate, Date endDate) {

        // create the where statement for the statement
        List<WhereClause> whereStatement = new ArrayList<>();

        // check that deleted records are not returned
        WhereClause whereDf = new WhereClause();
        whereDf.name = "df";
        whereDf.operator = WhereClause.Operators.NOT_EQUAL;
        whereDf.value = "1";
        whereDf.valueDataType = Integer.class;

        // add the id check
        WhereClause whereId = new WhereClause();
        whereId.conditional = WhereClause.CONDITIONALS.AND;
        whereId.name = "id";
        whereId.operator = WhereClause.Operators.EQUAL;
        whereId.value = Long.toString(id);
        whereId.valueDataType = Long.class;

        // check that the start date is less than or equal to the passed date
        WhereClause startDate1 = new WhereClause();
        startDate1.name = "arsd";
        startDate1.operator = WhereClause.Operators.LESS_THAN_OR_EQUAL;
        startDate1.value = GeneralConstants.DB_DATE_FORMAT.format(endDate);
        startDate1.valueDataType = Date.class;

        // check that the end date is greater than or equal to the passed date
        WhereClause endDate1 = new WhereClause();
        endDate1.conditional = WhereClause.CONDITIONALS.AND;
        endDate1.groupings.add(WhereClause.GROUPINGS.OPEN_PARENTHESIS);
        endDate1.name = "ared";
        endDate1.operator = WhereClause.Operators.GREATER_THAN_OR_EQUAL;
        endDate1.value = GeneralConstants.DB_DATE_FORMAT.format(startDate);
        endDate1.valueDataType = Date.class;

        // or that the end date is null (still active)
        WhereClause enddate2 = new WhereClause();
        enddate2.conditional = WhereClause.CONDITIONALS.OR;
        enddate2.name = "ared";
        enddate2.operator = WhereClause.Operators.IS;
        enddate2.value = WhereClause.NULL;
        enddate2.groupings.add(WhereClause.GROUPINGS.CLOSE_PARENTHESIS);

        whereStatement.add(whereDf);
        whereStatement.add(whereId);
        whereStatement.add(startDate1);
        whereStatement.add(endDate1);
        whereStatement.add(enddate2);

        // do the query
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, null, whereStatement);

        // create a List of entities
        return manageReturnedEntity(returnedStates);
    }


    /**
     * Takes a list of raw states returned by a query and organizes them into separate entities of type passed.
     * @param rawStates : List of states to organize into entities
     * //@param entityState : Class of entity to use for returned type.
     * @param <S> Parameterized Type of entity
     * @return List of Entities of Type passed
     */
    default <S extends CommonState> List<FdfEntity<S>> manageReturnedEntities(List<S> rawStates) {
        // create a List of entities
        List<FdfEntity<S>> allEntities = new ArrayList<>();

        for(S state: rawStates) {

            // see if this states entityId has already been seen
            int flag = 0;

            // compare this id against existing ones
            for(FdfEntity thisEntity : allEntities) {
                if (thisEntity.entityId == state.id) {

                    // match
                    flag++;
                    addStateToEntity(state, thisEntity);
                }
            }

            // state id was not found in existing entities, add a new one
            if(flag == 0) {
                // create a entity
                FdfEntity<S> entity = new FdfEntity<>();
                addStateToEntity(state, entity);
                allEntities.add(entity);

            }

        }
        return allEntities;
    }


    /**
     * Takes a list of raw states returned by a query and organizes them into separate entities of type passed.
     * @param rawStates : List of states to organize into entities
     * //@param entityState : Class of entity to use for returned type.
     * @param <S> Parameterized Type of entity
     * @return Entities of Type passed
     */
    default <S extends CommonState> FdfEntity<S> manageReturnedEntity(List<S> rawStates) {
        // create a List of entities
        FdfEntity<S> entity = new FdfEntity<>();

        for(S state: rawStates) {
                // add individual state to entity
                addStateToEntity(state, entity);
        }
        return entity;
    }

    /**
     * Manages the addition of a state to an entity. Ensures that the state is correctly added to the entity within
     * the current state or List of history states, as appropriate.  Also manages the entityId being set when the
     * first state is passed to an entity.
     *
     * @param state State to be added to an Entity
     * @param entity Entity that will have a state added
     * @param <S> Parameterized type of State.
     */
    @SuppressWarnings("unchecked")
    default <S extends CommonState> void addStateToEntity(CommonState state, FdfEntity<S> entity) {
        boolean flag = false;

        // check to see if this is the first state being saved to the entity, if so set the entityId
        if(entity.entityId == -1) {
            entity.entityId = state.id;
        }

        // check to see that the id of the state passed matches the existing id for this entity, otherwise it does
        // not belong here.
        if(entity.entityId == state.id) {

            // if there is history for the entity and this is not a current state, check to see if the passed state
            // is there already.
            if(!state.cf && entity.history.size() > 0) {
                // check to see if this record was already in history

                for (CommonState historyState: entity.history) {
                    if (historyState.rid == state.rid) {
                        flag = true;
                    }
                }
            }

            // set the record in the entity
            if (state.cf) {
                entity.current = (S) state;

            } else {
                if (!flag) {
                    entity.history.add((S) state);

                }
            }
        }
    }

    /**
     *
     * @param entityState
     * @param <S>
     * @return
     */
    default <S extends CommonState> long getNewEnityId(Class<S> entityState) {
        // get the last id assigned
        List<String> select = new ArrayList<>();
        String maxId = "max(id) as id";
        select.add(maxId);
        List<S> returnedStates = FdfPersistence.getInstance().selectQuery(entityState, select, null);
        if(returnedStates != null && returnedStates.size() == 1) {
            return returnedStates.get(0).id + 1;
        }
        return -1;
    }
}