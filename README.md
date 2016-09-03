A simple program to play with the visuals of dot-based graph layouts.
It is not designed for advanced visualization or graph file
editing.

You can load a .dot or .gv file with a graph specification in graphviz
format and visualize it. By playing with default properties, you can
alter what the graph looks like and decide on the optimal set of
paramaters. 

dotfiddle is not designed to be a deep editor of the graphviz format
as it is assumed that the graph is either typed in or generated in
another program.

dotfiddle curretly lacks polish and various features but it is good
enough to refine the look of a graph quickly.

Run `sbt stage` to generate a package you can run or 
`sbt universal:packageBin` to generate a distributable zip file.
