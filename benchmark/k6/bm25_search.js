import { createSearchWorkload } from './search_workload.js';

const workload = createSearchWorkload('BM25');
export const options = workload.options;
export default workload.execute;
