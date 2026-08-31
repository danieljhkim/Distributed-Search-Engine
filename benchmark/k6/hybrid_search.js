import { createSearchWorkload } from './search_workload.js';

const workload = createSearchWorkload('HYBRID');
export const options = workload.options;
export default workload.execute;
