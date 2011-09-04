package edu.stanford.nlp.mt.decoder.inferer.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import edu.stanford.nlp.mt.base.ConcreteTranslationOption;
import edu.stanford.nlp.mt.base.CoverageSet;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.RawSequence;
import edu.stanford.nlp.mt.base.RichTranslation;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.base.TranslationOption;
import edu.stanford.nlp.mt.decoder.inferer.AbstractInferer;
import edu.stanford.nlp.mt.decoder.recomb.RecombinationHash;
import edu.stanford.nlp.mt.decoder.util.ConstrainedOutputSpace;
import edu.stanford.nlp.mt.decoder.util.EnumeratedConstrainedOutputSpace;
import edu.stanford.nlp.mt.decoder.util.Hypothesis;
import edu.stanford.nlp.mt.decoder.util.OptionGrid;
import edu.stanford.nlp.mt.decoder.util.PhraseGenerator;
import edu.stanford.nlp.mt.decoder.util.Scorer;
import edu.stanford.nlp.util.Pair;

/**
 * Prefix decoder 
 * 
 * @author daniel
 *
 * @param <TK>
 * @param <FV>
 */
public class PrefixDecoder<TK, FV> extends AbstractInferer<TK, FV> {
  public static boolean DEBUG = true;
  
  int maxDistortion = -1;
  public PrefixDecoder(AbstractInferer<TK, FV> inferer) {
    super(inferer);
    if (inferer instanceof MultiBeamDecoder) {
      MultiBeamDecoder<TK, FV> mbd = (MultiBeamDecoder<TK, FV>)inferer;
      maxDistortion = mbd.maxDistortion;
    } else if (inferer instanceof DTUDecoder) {
      throw new UnsupportedOperationException();
    }
  }
  
  public PhraseGenerator<TK> getPhraseGenerator() {
    return phraseGenerator;
  }
  
  @Override
  public RichTranslation<TK, FV> translate(Sequence<TK> foreign,
      int translationId, ConstrainedOutputSpace<TK, FV> constrainedOutputSpace,
      List<Sequence<TK>> targets) {
    throw new UnsupportedOperationException();
  }


  @Override
  public RichTranslation<TK, FV> translate(Scorer<FV> scorer,
      Sequence<TK> foreign, int translationId,
      ConstrainedOutputSpace<TK, FV> constrainedOutputSpace,
      List<Sequence<TK>> targets) {
    throw new UnsupportedOperationException();
  }

  public Map<Pair<Sequence<TK>,Sequence<TK>>,PriorityQueue<Hypothesis<TK, FV>>> hypCache = new
		  HashMap<Pair<Sequence<TK>,Sequence<TK>>,PriorityQueue<Hypothesis<TK, FV>>>();
  
  @SuppressWarnings({ "rawtypes", "unchecked" })
@Override
  public List<RichTranslation<TK, FV>> nbest(Scorer<FV> scorer,
      Sequence<TK> foreign, int translationId,
      ConstrainedOutputSpace<TK, FV> constrainedOutputSpace,
      List<Sequence<TK>> targets, int size) {
    
    PriorityQueue<Hypothesis<TK, FV>> agenda = new PriorityQueue<Hypothesis<TK,FV>>();
    PriorityQueue<Hypothesis<TK, FV>> paused = new PriorityQueue<Hypothesis<TK,FV>>();
    int windowSize = 100;
    int maxPrefixCompletion = 0;
    
    List<ConcreteTranslationOption<TK>> options = phraseGenerator.translationOptions(foreign, targets, translationId);
    List<ConcreteTranslationOption<TK>> filteredOptions = constrainedOutputSpace.filterOptions(options);
    float[] autoInsertScores = new float[options.get(0).abstractOption.scores.length];
    String[] scoreNames = options.get(0).abstractOption.phraseScoreNames;
    
    if (DEBUG) {
    	System.err.println("filtered options (for prefix)");
    	System.err.println("========================================");
    	for (ConcreteTranslationOption<TK> cto : filteredOptions) {
    	   System.err.printf(" - %s -> %s (%s)\n", cto.abstractOption.foreign, cto.abstractOption.translation, cto.foreignPos);
    	}
    	
    	System.err.println("unfiltered options (for suffix)");
    	System.err.println("========================================");
    	for (ConcreteTranslationOption<TK> cto : options) {
    	   System.err.printf(" - %s -> %s (%s)\n", cto.abstractOption.foreign, cto.abstractOption.translation, cto.foreignPos);
    	}
    }
    
    Arrays.fill(autoInsertScores, -10000);
    OptionGrid<TK> optionGrid = new OptionGrid<TK>(options, foreign);
    OptionGrid<TK> filteredOptionGrid = new OptionGrid<TK>(filteredOptions, foreign);
    
    
    // use *UNFILTERED* options for heuristic calculation
    Hypothesis<TK, FV> nullHyp = new Hypothesis<TK, FV>(translationId, foreign, heuristic, annotators, Arrays.asList(options));
    featurizer.initialize(options, foreign);
    if (DEBUG) {
    	System.err.printf("Adding initial hypothesis: %s\n", nullHyp);
    }
    /*
    EnumeratedConstrainedOutputSpace<TK, FV> ecos = (EnumeratedConstrainedOutputSpace<TK, FV>)constrainedOutputSpace;
    Sequence<TK> prefix = ecos.allowableSequences.get(0);
    for (int i = prefix.size(); i > 0; i--) {
    	Sequence<TK> subSeq = prefix.subsequence(0, i);
    	PriorityQueue<Hypothesis<TK, FV>> savedHyps = hypCache.get(
    			new Pair<Sequence<TK>,Sequence<TK>>(foreign, subSeq));
    	if (savedHyps != null) {
    		System.err.printf("Recovering saved hyps for prefix: %s\n", subSeq);
    		agenda.addAll(savedHyps);
    		break;
    	}
    }
    if (agenda.size() == 0) {
      System.err.printf("Could not recover saved hyps for prefix: %s\n", prefix);
      //System.err.println("Only have saved hyps for prefixes:");
      //for (Sequence<TK> prefix )
      agenda.add(nullHyp);
    } */
    agenda.add(nullHyp);
    
    List<Hypothesis<TK, FV>> completePrefixes = new ArrayList<Hypothesis<TK,FV>>();
    int foreignSz = foreign.size();
    long startTime = System.currentTimeMillis();
    RecombinationHash<Hypothesis<TK,FV>> recombinationHash = new RecombinationHash<Hypothesis<TK,FV>>(filter);   
    do {
     /* long time = System.currentTimeMillis() - startTime;
      if (completePrefixes.size() != 0 && time > 20000) {
          System.out.printf("Time limit exceeded: %s > %d\n", time, 300);
    	  break;
      } */
      if (agenda.size() == 0) {
    	  agenda.addAll(paused);
    	  paused.clear();
    	  windowSize++;
    	  System.err.printf("Doing window size: %d\n", windowSize);
      }
      Hypothesis<TK, FV> hyp = agenda.remove();
      
      if (hyp.featurizable != null && maxPrefixCompletion - hyp.featurizable.partialTranslation.size() > windowSize) {
    	  System.err.printf("pausing off agenda %d > %d\n", maxPrefixCompletion - 
    			  hyp.featurizable.partialTranslation.size(), 
    			  windowSize);
    	  System.err.printf("adding to paused (max: %d size: %d window %d)",maxPrefixCompletion, hyp.featurizable.partialTranslation.size(), windowSize);
    	  paused.add(hyp);
    	  continue;
      }
      if (DEBUG) {
    	  System.err.printf("[Prefix] Expanding hypothesis: %s\n", hyp);
      }
      int firstCoverageGap = hyp.foreignCoverage.nextClearBit(0);
      int expansions = 0;     
      for (int startPos = firstCoverageGap; startPos < foreignSz; startPos++) {
        int endPosMax = hyp.foreignCoverage.nextSetBit(startPos);

        // check distortion limit
        if (endPosMax < 0) {
          if (maxDistortion >= 0 && startPos != firstCoverageGap) {
            endPosMax = Math.min(firstCoverageGap + maxDistortion + 1,
                foreignSz);
          } else {
            endPosMax = foreignSz;
          }
        }
        
        for (int endPos = startPos; endPos < endPosMax; endPos++) {
       // use *FILTERED* options for prefix hypothesis expansion
          List<ConcreteTranslationOption<TK>> applicableOptions = filteredOptionGrid
              .get(startPos, endPos);
          System.err.printf("%d-%d: option count: %d\n", startPos, endPos, applicableOptions.size());
          for (ConcreteTranslationOption<TK> option : applicableOptions) {
            if (constrainedOutputSpace != null
                && !constrainedOutputSpace.allowableContinuation(
                    hyp.featurizable, option)) {
              EnumeratedConstrainedOutputSpace<TK, FV> ecos = (EnumeratedConstrainedOutputSpace<TK, FV>)constrainedOutputSpace;
              Sequence<TK> prefix = ecos.allowableSequences.get(0);   
              System.err.printf("not allowed: '%s'+'%s'\n", (hyp.featurizable == null ? "" : hyp.featurizable.partialTranslation), option.abstractOption.translation);
              System.err.printf("doesn't match prefix: '%s'\n", prefix);
              String wilcoTango = (hyp.featurizable == null ? "" : hyp.featurizable.partialTranslation.toString()) +
            		              " " + option.abstractOption.translation.toString();
              if (!prefix.toString().startsWith(wilcoTango)) {
                 continue;
              } else {
            	  System.err.println("wilco Tango");
              }
            }
            Hypothesis<TK, FV> newHyp = new Hypothesis<TK, FV>(translationId,
                option, hyp.length, hyp, featurizer, scorer, heuristic);
            RecombinationHash.Status status = recombinationHash.queryStatus(newHyp,
                    true);
            if (status == RecombinationHash.Status.COMBINABLE) {
            	System.err.printf("Recombining hyp: %s\n", newHyp.featurizable.partialTranslation);
            	continue;
            }
            System.err.println("New hyp:");
            for (Hypothesis<TK, FV> h = newHyp; h.featurizable != null; h = h.preceedingHyp) {
            	System.err.printf("  f: '%s' => e: '%s'\n", h.featurizable.foreignPhrase, h.featurizable.translatedPhrase);
            }
            System.err.println();
            if (newHyp.featurizable.untranslatedTokens != 0) {
              if (constrainedOutputSpace != null
                  && !constrainedOutputSpace
                      .allowablePartial(newHyp.featurizable)) {
            	 continue;
              }
            }
            
            expansions++;    
            
            int completion = newHyp.featurizable.partialTranslation.size();   
            if (completion > maxPrefixCompletion) {
            	maxPrefixCompletion = completion;
            	System.err.printf("new max completion: %d\n", maxPrefixCompletion);
            }
            
            if (constrainedOutputSpace != null
                && constrainedOutputSpace
                    .allowableFinal(newHyp.featurizable)) {
              if (DEBUG) {
            	  System.out.printf(" - allowable final\n");
              }
              completePrefixes.add(newHyp);
              continue;
            }
            
            
            if (maxPrefixCompletion - completion <= windowSize) {
               agenda.add(newHyp);
            } else {
            	System.err.printf("Pausing - not within window %d !<= %d\n", maxPrefixCompletion - completion, windowSize);
            	System.err.printf("[adding to paused (max: %d size: %d window %d)]",maxPrefixCompletion, 
            			completion, windowSize);
            	paused.add(newHyp);
            }
          }      
        } 
      }
        if (expansions == 0) {
        	List<Sequence<TK>> allowableSequences = constrainedOutputSpace.getAllowableSequences();
        	for (Sequence<TK> allowableSequence : allowableSequences) {
        		if (hyp.featurizable != null &&
        		    !allowableSequence.startsWith(hyp.featurizable.partialTranslation)) {
        			continue;
        		}
        		int hypSz;
        		if (hyp.featurizable == null) {
        		  hypSz = 0;
        		} else {
        		  hypSz = hyp.featurizable.partialTranslation.size();	
        		}
        		if (hypSz >= allowableSequence.size()) {
        			continue;
        		}
        		
        		IString nextWord = (IString)allowableSequence.get(hypSz);
	        	TranslationOption<IString> abstractOption = new TranslationOption<IString>(autoInsertScores, scoreNames, 
	        	   new RawSequence<IString>(new IString[]{nextWord}), new RawSequence<IString>(new IString[0]), null);
	        	
	        	ConcreteTranslationOption option = new ConcreteTranslationOption(
	        			abstractOption, new CoverageSet(), 0, "autogenerated", -100);
	        	Hypothesis<TK, FV> newHyp = new Hypothesis<TK, FV>(translationId,
	                    option, hyp.length, hyp, featurizer, scorer, heuristic);
	        	if (DEBUG) {
	        		System.err.printf("Autogenerated hyp: %s\n", newHyp);
	        	}
	        
	        	RecombinationHash.Status status = recombinationHash.queryStatus(newHyp,
	                    true);
	            if (status == RecombinationHash.Status.COMBINABLE) {
	            	continue;
	            }
	            
	        	if (constrainedOutputSpace != null
	                    && constrainedOutputSpace
	                        .allowableFinal(newHyp.featurizable)) {
	                  if (DEBUG) {
	                	  System.out.printf(" - allowable final\n");
	                  }
	                  completePrefixes.add(newHyp);
	                  continue;
	            }
	        		        	
	        	int completion = newHyp.featurizable.partialTranslation.size();
	        	if (completion > maxPrefixCompletion) {
	            	maxPrefixCompletion = completion;
	            	System.err.printf("new max completion (faked): %d\n", maxPrefixCompletion);
	            }
	        	if (maxPrefixCompletion - completion <= windowSize) {
	                System.err.printf("Within window %d <= %d\n", maxPrefixCompletion - completion, windowSize);
	                agenda.add(newHyp);
	             } else {
	             	System.err.printf("Pausing - not within window %d !<= %d\n", maxPrefixCompletion - completion, windowSize);
	                paused.add(newHyp);
	             }
        	}
        }
       
    } while (agenda.size() > 0 || paused.size() > 0);
    PriorityQueue<Hypothesis<TK, FV>> allHyps = new PriorityQueue<Hypothesis<TK,FV>>();
    allHyps.addAll(agenda);
    allHyps.addAll(paused);
    allHyps.addAll(completePrefixes);
    
    Pair<Sequence<TK>,Sequence<TK>> cacheKey =
    		new Pair<Sequence<TK>,Sequence<TK>>(foreign,completePrefixes.get(0).featurizable.partialTranslation);
    hypCache.put(cacheKey, allHyps);
    
    agenda.clear();
    if (DEBUG) {
      System.err.printf("Doing prediction stage with prefix hypotheses: %s\n", completePrefixes.size());
    }
    agenda.addAll(completePrefixes);
    List<Hypothesis<TK, FV>> predictions = new ArrayList<Hypothesis<TK,FV>>(PREDICTIONS);
    do {
      Hypothesis<TK, FV> hyp = agenda.remove();
      if (DEBUG) {
        System.err.printf("[pred loop] Removing hyp from agenda: %s\n", hyp);
      }
      int firstCoverageGap = hyp.foreignCoverage.nextClearBit(0);     
      for (int startPos = firstCoverageGap; startPos < foreignSz; startPos++) {
        int endPosMax = hyp.foreignCoverage.nextSetBit(startPos);

        // check distortion limit
        if (endPosMax < 0) {
          if (maxDistortion >= 0 && startPos != firstCoverageGap) {
            endPosMax = Math.min(firstCoverageGap + maxDistortion + 1,
                foreignSz);
          } else {
            endPosMax = foreignSz;
          }
        }
        for (int endPos = startPos; endPos < endPosMax; endPos++) {
          // use *UNFILTERED* options for prefix hypothesis expansion predictions
          List<ConcreteTranslationOption<TK>> applicableOptions = optionGrid
              .get(startPos, endPos);
          for (ConcreteTranslationOption<TK> option : applicableOptions) {
        	if (option.abstractOption.foreign.equals(option.abstractOption.translation)) {
        		if (DEBUG) {
        			System.err.println("ignoring option since source phrase == target phrase");
        			System.err.printf("'%s'='%s'\n", option.abstractOption.foreign, option.abstractOption.translation);
        		}
        		continue;
        	}
            Hypothesis<TK, FV> newHyp = new Hypothesis<TK, FV>(translationId,
                option, hyp.length, hyp, featurizer, scorer, heuristic);
            if (DEBUG) {
            	System.out.printf("constructed new hyp: %s\n", newHyp);
            }
            predictions.add(newHyp);                      
            agenda.add(newHyp);
          }
        }
      }      
    } while (predictions.size() < PREDICTIONS && agenda.size() > 0);
    
    List<RichTranslation<TK, FV>> nbest = new ArrayList<RichTranslation<TK,FV>>(predictions.size());
    for (Hypothesis<TK,FV> hyp : predictions) {
      nbest.add(new RichTranslation<TK, FV>(hyp.featurizable, hyp.finalScoreEstimate(), null));
    }
    
    System.err.println("Alignments\n==========");
    for (int i = 0; i < Math.min(10, predictions.size()); i++) {
    	System.err.printf("Hypothesis: %d (score: %f)\n", i, predictions.get(i).score);
    	List<String> alignments = new LinkedList<String>();
    	for (Hypothesis<TK, FV> hyp = predictions.get(i); hyp.featurizable != null; hyp = hyp.preceedingHyp) {
    		alignments.add(String.format("f:'%s' => e: '%s' [%s]", hyp.featurizable.foreignPhrase, 
    				hyp.featurizable.translatedPhrase, Arrays.toString(hyp.translationOpt.abstractOption.scores)));
    	}
        Collections.reverse(alignments);
    	for (String alignment : alignments) {
    	   System.err.print("   ");
    	   System.err.println(alignment);
    	}
    }
    
    return nbest;
  }

  static public final int PREFIX_ALIGNMENTS = 100;
  static public final int PREDICTIONS = 100;
  @Override
  public List<RichTranslation<TK, FV>> nbest(Sequence<TK> foreign,
      int translationId, ConstrainedOutputSpace<TK, FV> constrainedOutputSpace,
      List<Sequence<TK>> targets, int size) {
    return nbest(scorer, foreign, translationId, constrainedOutputSpace, targets, size);
  }
}
