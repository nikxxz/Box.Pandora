export { labelImage, recognizeText } from './MLBridgeModule';

export {
  embedImage,
  getEmbeddingModelInfo,
  getImageStats,
  MODEL_VERSION,
  EMBEDDING_DIM,
  INPUT_SIZE,
} from './EmbeddingBridgeModule';

export { HeuristicTagger } from './HeuristicTagger';
export { SmartTagSuggestionEngine } from './SmartTagSuggestionEngine';
export { EmbeddingIndexer } from './EmbeddingIndexer';
export { TaggingService } from './TaggingService';
export { PrototypeBackfillService } from '../database/PrototypeBackfillService';
