import { createSearchWorkload } from './search_workload.js';

// This distinct entrypoint makes semantic target selection observable and testable.
const workload = createSearchWorkload('SEMANTIC');
export const options = workload.options;
export default workload.execute;
